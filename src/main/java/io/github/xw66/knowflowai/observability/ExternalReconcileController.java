package io.github.xw66.knowflowai.observability;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import io.github.xw66.knowflowai.document.DocumentStorage;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import io.github.xw66.knowflowai.retrieval.LuceneIndex;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

@RestController
@RequestMapping("/api/admin/reconcile/documents")
public class ExternalReconcileController {
    private static final int LIMIT=2000;
    private static final String SNAPSHOT="""
            SELECT d.id,d.status,d.index_version,d.active_index_version,d.vector_collection,d.storage_key,d.sha256,
              p.index_version AS bm25_version,p.instance_id,p.status AS bm25_status
            FROM document d LEFT JOIN bm25_index_progress p ON p.document_id=d.id
            WHERE d.knowledge_base_id=:base
            """;
    private final JdbcClient jdbc;
    private final DocumentStorage storage;
    private final ObjectProvider<QdrantIndex> vector;
    private final ObjectProvider<LuceneIndex> bm25;

    public ExternalReconcileController(JdbcClient jdbc,DocumentStorage storage,ObjectProvider<QdrantIndex> vector,ObjectProvider<LuceneIndex> bm25) {
        this.jdbc=jdbc; this.storage=storage; this.vector=vector; this.bm25=bm25;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="分页核验文件和外部索引",description="管理员只读检查，必须指定知识库。afterId 为文档游标，每页最多 10 篇，每篇最多核验 2000 分块；PARTIAL 不代表完整通过。UNKNOWN/UNAVAILABLE 不代表缺失。并发状态变化标记 CHANGED，请重查。只核验数据库所记集合，不扫描所有集合或孤立文件，不执行清理。")
    public ResponseEntity<Page> report(@RequestParam @Positive long knowledgeBaseId,
            @RequestParam(defaultValue="0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue="5") @Min(1) @Max(10) int limit) {
        var documents=jdbc.sql(SNAPSHOT+" AND d.id>:after ORDER BY d.id LIMIT :limit")
                .param("base",knowledgeBaseId).param("after",afterId).param("limit",limit+1).query(Snapshot.class).list();
        boolean more=documents.size()>limit;
        var results=new ArrayList<DocumentCheck>();
        for(var doc:documents.subList(0,Math.min(limit,documents.size()))) {
            boolean active=doc.activeIndexVersion()!=null && !doc.status().equals("DELETED");
            var chunks=jdbc.sql("SELECT id,chunk_index FROM document_chunk WHERE document_id=:id AND index_version=:version ORDER BY id LIMIT 2001")
                    .param("id",doc.id()).param("version",doc.activeIndexVersion()==null?0:doc.activeIndexVersion()).query(Chunk.class).list();
            var file=checkFile(doc);
            var qdrant=checkVector(doc,knowledgeBaseId,chunks,active);
            var lucene=checkBm25(doc,knowledgeBaseId,chunks,active);
            // 外部检查不占用数据库锁，结束后重新读取激活版本和进度，避免把切换中的快照当成稳定结果。
            var current=jdbc.sql(SNAPSHOT+" AND d.id=:id").param("base",knowledgeBaseId).param("id",doc.id()).query(Snapshot.class).optional();
            String consistency=current.isPresent() && current.get().equals(doc)?"OBSERVED":"CHANGED";
            results.add(new DocumentCheck(doc.id(),doc.status(),doc.activeIndexVersion(),consistency,
                    chunks.size()>LIMIT?"PARTIAL":active && chunks.isEmpty()?"MISSING_CHUNKS":"OBSERVED",file,qdrant,lucene));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(Instant.now(),knowledgeBaseId,
                more?results.getLast().documentId():null,List.copyOf(results)));
    }

    @GetMapping("/files")
    @io.swagger.v3.oas.annotations.Operation(summary="分页扫描文档存储目录",description="管理员只读扫描配置的文档目录；只处理目录直属项，不递归其他目录，不删除文件。afterKey 是文件名游标，最多 100 项。UNAVAILABLE 表示目录读取失败，不能解释为孤立文件。")
    public ResponseEntity<FilePage> files(@RequestParam(defaultValue="") String afterKey,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        final io.github.xw66.knowflowai.document.DocumentStorage.FilePage listed;
        try { listed=storage.listFiles(afterKey,limit); }
        catch (java.io.IOException error) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .body(new FilePage(Instant.now(),"UNAVAILABLE",null,List.of()));
        }
        var result=listed.files().stream().map(file -> {
            var document=jdbc.sql("SELECT id,status FROM document WHERE storage_key=:key").param("key",file.key())
                    .query(DocumentRef.class).optional();
            String status = "SYMLINK".equals(file.storageStatus()) || "DIRECTORY_OR_SPECIAL".equals(file.storageStatus())
                    || "UNREADABLE".equals(file.storageStatus()) ? file.storageStatus()
                    : ".upload-".equals(file.key()) || file.key().startsWith(".upload-") ? "TEMPORARY"
                    : document.isPresent() ? "REGISTERED" : "ORPHAN";
            return new FileResult(file.key(),status,file.sizeBytes(),document.map(DocumentRef::id).orElse(null),document.map(DocumentRef::status).orElse(null));
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FilePage(Instant.now(),"OK",
                listed.more() ? result.getLast().key() : null,List.copyOf(result)));
    }

    private Check checkFile(Snapshot doc) {
        try {
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(storage.read(doc.storageKey())));
            return state(digest.equals(doc.sha256())?(doc.status().equals("DELETED")?"RETAINED":"OK"):"MISMATCH");
        } catch(NoSuchFileException error) { return state("MISSING"); }
        catch(Exception error) { return state("UNAVAILABLE"); }
    }

    private Check checkVector(Snapshot doc,long base,List<Chunk> chunks,boolean active) {
        if(vector.getIfAvailable()==null) return state("NOT_CONFIGURED");
        if(doc.vectorCollection()==null) return state(active?"MISSING_COLLECTION_METADATA":"NOT_REQUIRED");
        if(chunks.size()>LIMIT) return state("PARTIAL");
        try {
            var response=vector.getObject().inspectDocument(doc.vectorCollection(),doc.id(),LIMIT+1);
            if(response==null || !"ok".equals(response.path("status").asText()) || !response.path("result").path("points").isArray()) return state("UNAVAILABLE");
            var points=response.path("result").path("points");
            if(points.size()>LIMIT || !response.path("result").path("next_page_offset").isNull()
                    && !response.path("result").path("next_page_offset").isMissingNode()) return state("PARTIAL");
            if(!active) return new Check(points.isEmpty()?"NOT_REQUIRED":doc.status().equals("DELETED")?"PENDING_CLEANUP":"PENDING",0,points.size(),0,List.of());
            var expected=new HashMap<String,Long>();
            for(var chunk:chunks) expected.put(UUID.nameUUIDFromBytes((doc.id()+":"+doc.activeIndexVersion()+":"+chunk.chunkIndex()).getBytes(StandardCharsets.UTF_8)).toString(),chunk.id());
            var found=new HashSet<Long>();
            int invalid=0,retained=0;
            for(var point:points) {
                var payload=point.path("payload");
                if(!payload.path("document_id").isIntegralNumber() || !payload.path("knowledge_base_id").isIntegralNumber()
                        || !payload.path("index_version").isIntegralNumber() || !payload.path("chunk_id").isIntegralNumber()) return state("UNAVAILABLE");
                if(payload.path("index_version").asInt(-1)!=doc.activeIndexVersion()) { retained++; continue; }
                Long id=expected.get(point.path("id").asText());
                if(id==null || payload.path("chunk_id").asLong(-1)!=id || payload.path("document_id").asLong(-1)!=doc.id()
                        || payload.path("knowledge_base_id").asLong(-1)!=base || !found.add(id)) invalid++;
            }
            return compare(chunks,found,invalid,retained);
        } catch(HttpClientErrorException.NotFound error) { return state(active?"MISSING":"NOT_REQUIRED"); }
        catch(RuntimeException error) { return state("UNAVAILABLE"); }
    }

    private Check checkBm25(Snapshot doc,long base,List<Chunk> chunks,boolean active) {
        if(bm25.getIfAvailable()==null) return state("NOT_CONFIGURED");
        if(chunks.size()>LIMIT) return state("PARTIAL");
        try {
            var batch=bm25.getObject().inspectDocument(base,doc.id(),LIMIT+1);
            if(batch.candidates().size()>LIMIT) return state("PARTIAL");
            if(!active) return state(batch.candidates().isEmpty()?"NOT_REQUIRED":doc.status().equals("DELETED")?"PENDING_CLEANUP":"PENDING");
            if(!"READY".equals(doc.bm25Status()) || !Objects.equals(doc.bm25Version(),doc.activeIndexVersion())) return state("PENDING");
            if(!Objects.equals(batch.instanceId(),doc.instanceId())) return state("INSTANCE_MISMATCH");
            var found=new HashSet<Long>();
            int invalid=0;
            for(var hit:batch.candidates()) {
                if(hit.indexVersion()!=doc.activeIndexVersion() || !found.add(hit.chunkId())) invalid++;
            }
            return compare(chunks,found,invalid,0);
        } catch(NoSuchFileException error) { return state(active?"MISSING":"NOT_REQUIRED"); }
        catch(Exception error) { return state("UNAVAILABLE"); }
    }

    private Check compare(List<Chunk> chunks,Set<Long> found,int invalid,int retained) {
        var expected=chunks.stream().map(Chunk::id).collect(java.util.stream.Collectors.toSet());
        var missing=expected.stream().filter(id->!found.contains(id)).sorted().toList();
        long extra=found.stream().filter(id->!expected.contains(id)).count();
        String status=invalid>0 || extra>0?"MISMATCH":!missing.isEmpty()?"MISSING":chunks.isEmpty()?"MISSING_CHUNKS":"OK";
        return new Check(status,chunks.size(),found.size(),retained,missing.stream().limit(20).toList());
    }
    private static Check state(String status) { return new Check(status,null,null,null,List.of()); }
    public record Check(String status,Integer expectedChunks,Integer observedChunks,Integer retainedOtherVersionPoints,List<Long> missingChunkIds) {}
    public record DocumentCheck(long documentId,String documentStatus,Integer activeVersion,String consistency,String database,Check file,Check qdrant,Check lucene) {}
    public record Page(Instant generatedAt,long knowledgeBaseId,Long nextAfterId,List<DocumentCheck> documents) {}
    public record FilePage(Instant generatedAt,String storageStatus,String nextAfterKey,List<FileResult> files) {}
    public record FileResult(String key,String status,Long sizeBytes,Long documentId,String documentStatus) {}
    private record Chunk(long id,int chunkIndex) {}
    private record Snapshot(long id,String status,int indexVersion,Integer activeIndexVersion,String vectorCollection,String storageKey,String sha256,Integer bm25Version,String instanceId,String bm25Status) {}
    private record DocumentRef(long id,String status) {}
}
