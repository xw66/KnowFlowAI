package io.github.xw66.knowflowai.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Comparator;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class SearchService {
    private final JdbcClient jdbc;
    private final KnowledgeBaseService bases;
    private final ObjectProvider<EmbeddingModel> models;
    private final ObjectProvider<QdrantIndex> indexes;
    private final ObjectProvider<LuceneIndex> lexicalIndexes;
    private final ObjectProvider<RerankClient> rerankers;
    private final TransactionTemplate transaction;
    private final io.github.xw66.knowflowai.ingestion.EmbeddingCalls calls;

    public SearchService(JdbcClient jdbc, KnowledgeBaseService bases, ObjectProvider<EmbeddingModel> models,
            ObjectProvider<QdrantIndex> indexes, ObjectProvider<LuceneIndex> lexicalIndexes,
            ObjectProvider<RerankClient> rerankers, PlatformTransactionManager manager, io.github.xw66.knowflowai.ingestion.EmbeddingCalls calls) {
        this.jdbc = jdbc; this.bases = bases; this.models = models; this.indexes = indexes;
        this.lexicalIndexes=lexicalIndexes;
        this.rerankers=rerankers;
        this.transaction = new TransactionTemplate(manager);
        this.calls=calls;
    }

    public List<Hit> search(long userId, long baseId, String query, int topK) {
        return search(userId,baseId,query,topK,Mode.VECTOR);
    }

    public List<Hit> search(long userId, long baseId, String query, int topK, Mode mode) {
        return search(userId,baseId,query,topK,mode,false).hits();
    }

    public SearchResult search(long userId, long baseId, String query, int topK, Mode mode, boolean rerank) {
        if (rerank && mode!=Mode.HYBRID) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只有 HYBRID 支持重排");
        bases.get(userId, baseId);
        var reranker=rerankers.getIfAvailable();
        var model=models.getIfAvailable();
        var vectorIndex=indexes.getIfAvailable();
        var lexicalIndex=lexicalIndexes.getIfAvailable();
        // 开始模型调用前确认两路配置，HYBRID 不能悄悄降为单路检索。
        if ((mode!=Mode.BM25 && (model==null || vectorIndex==null)) || (mode!=Mode.VECTOR && lexicalIndex==null)) {
            throw unavailable();
        }
        Selection vector;
        Selection lexical;
        try {
            vector=mode==Mode.BM25 ? null : vectorCandidates(model,vectorIndex,baseId,query);
            var batch=mode==Mode.VECTOR ? null : lexicalIndex.search(baseId,query,200);
            lexical=batch==null ? null : new Selection(batch.candidates(),Mode.BM25,batch.instanceId());
        } catch (java.io.IOException | RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn()
                    .addKeyValue("mode",mode).addKeyValue("exceptionType",exception.getClass().getSimpleName()).log("检索不可用");
            throw unavailable();
        }
        var hits=readAuthorized(userId,baseId,rerank && reranker!=null ? reranker.candidates() : topK,mode,vector,lexical);
        if (!rerank) return new SearchResult(hits,RerankStatus.NOT_REQUESTED,null);
        if (reranker==null) return new SearchResult(hits,RerankStatus.DISABLED,null);
        if (hits.size()<2) return new SearchResult(hits,RerankStatus.INSUFFICIENT_CANDIDATES,null);
        List<Hit> ordered;
        RerankStatus rerankStatus;
        try {
            ordered=reranker.rank(query,hits);
            rerankStatus=RerankStatus.APPLIED;
        } catch (java.io.IOException | RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("exceptionType",exception.getClass().getSimpleName())
                    .log("重排失败，退回 RRF 顺序");
            ordered=hits;
            rerankStatus=RerankStatus.FALLBACK;
        }
        // 外部服务不能扩张候选范围；请求期间撤权、删除或版本切换后，旧片段必须被剔除。
        var allowed=new HashMap<Long,Hit>();
        for (var hit : readAuthorized(userId,baseId,400,mode,vector,lexical)) allowed.put(hit.chunkId(),hit);
        var result=ordered.stream().filter(hit -> allowed.containsKey(hit.chunkId())).limit(topK).map(hit -> {
            var current=allowed.get(hit.chunkId());
            return new Hit(current.chunkId(),current.documentId(),current.documentName(),current.content(),current.pageNumber(),current.paragraphNumber(),hit.score());
        }).toList();
        return new SearchResult(result,rerankStatus,rerankStatus==RerankStatus.APPLIED ? reranker.model() : null);
    }

    private List<Hit> readAuthorized(long userId, long baseId, int topK, Mode mode, Selection vector, Selection lexical) {
        return transaction.execute(status -> {
            // 两路外部查询结束后，在同一事务内重新授权，成员修改与此共享锁互斥。
            jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id",baseId).query(Long.class).optional();
            bases.get(userId,baseId);
            if (mode==Mode.VECTOR) return hydrate(userId,baseId,topK,vector);
            if (mode==Mode.BM25) return hydrate(userId,baseId,topK,lexical);
            // 先分别过滤并保留完整候选排名，再融合；不能先截断每路 topK。
            return fuse(hydrate(userId,baseId,200,vector),hydrate(userId,baseId,200,lexical),topK);
        });
    }

    private Selection vectorCandidates(EmbeddingModel model, QdrantIndex index, long baseId, String query) {
        var points=index.search(calls.embed(model,List.of(query),null).getFirst(),baseId,200).path("result").path("points");
        if (!points.isArray() || points.size()>200) throw new IllegalStateException("向量响应格式无效");
        var candidates=new ArrayList<Candidate>();
        for (JsonNode point : points) {
            var payload=point.path("payload");
            candidates.add(new Candidate(payload.path("chunk_id").asLong(-1),payload.path("document_id").asLong(-1),
                    payload.path("knowledge_base_id").asLong(-1),payload.path("index_version").asInt(-1),
                    point.path("score").asDouble(Double.NaN)));
        }
        return new Selection(candidates,Mode.VECTOR,index.collection());
    }

    private List<Hit> hydrate(long userId, long baseId, int topK, Selection selection) {
        var hits = new ArrayList<Hit>();
        var seen = new HashSet<Long>();
        for (Candidate point : selection.candidates()) {
            long chunkId=point.chunkId(), documentId=point.documentId();
            int version=point.indexVersion();
            double score=point.score();
            if (point.knowledgeBaseId()!=baseId || chunkId<=0 || !Double.isFinite(score)) continue;
            var candidate = jdbc.sql("""
                    SELECT c.id AS chunk_id, d.id AS document_id, d.name AS document_name,
                        c.content, c.page_number, c.paragraph_number
                    FROM document_chunk c JOIN document d ON d.id=c.document_id
                    JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
                    JOIN knowledge_access km ON km.knowledge_base_id=kb.id AND km.user_id=:user
                    JOIN app_user u ON u.id=km.user_id
                    WHERE c.id=:chunk AND d.id=:document AND kb.id=:base AND kb.status='ACTIVE' AND u.status='ACTIVE'
                        AND d.status='READY' AND c.index_version=d.active_index_version
                        AND c.index_version=:version
                        AND ((:mode='VECTOR' AND d.vector_collection=:scope) OR (:mode='BM25' AND EXISTS (
                            SELECT 1 FROM bm25_index_progress p WHERE p.document_id=d.id AND p.index_version=c.index_version
                              AND p.instance_id=:scope AND p.status='READY' AND p.committed_at IS NOT NULL)))
                    """).param("chunk", chunkId).param("document", documentId).param("base", baseId)
                    .param("user", userId).param("version", version).param("scope",selection.scope()).param("mode",selection.mode().name())
                    .query((rs, row) -> new Hit(rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("document_name"),
                            rs.getString("content"), rs.getObject("page_number", Integer.class), rs.getInt("paragraph_number"), score)).optional();
            if (candidate.isPresent() && seen.add(chunkId)) hits.add(candidate.get());
            if (hits.size() == topK) break;
        }
        return List.copyOf(hits);
    }

    static List<Hit> fuse(List<Hit> vector, List<Hit> lexical, int topK) {
        var hits=new HashMap<Long,Hit>();
        var scores=new HashMap<Long,Double>();
        for (var route : List.of(vector,lexical)) {
            var seen=new HashSet<Long>();
            int rank=0;
            for (var hit : route) {
                if (!seen.add(hit.chunkId())) continue;
                rank++;
                hits.putIfAbsent(hit.chunkId(),hit);
                scores.merge(hit.chunkId(),1.0/(60+rank),Double::sum);
            }
        }
        return hits.values().stream()
                .sorted(Comparator.<Hit>comparingDouble(hit -> scores.get(hit.chunkId())).reversed().thenComparingLong(Hit::chunkId))
                .limit(topK).map(hit -> new Hit(hit.chunkId(),hit.documentId(),hit.documentName(),hit.content(),
                        hit.pageNumber(),hit.paragraphNumber(),scores.get(hit.chunkId()))).toList();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "检索暂时不可用，请检查模型与索引配置");
    }
    public enum Mode { VECTOR, BM25, HYBRID }
    public enum RerankStatus { NOT_REQUESTED, DISABLED, INSUFFICIENT_CANDIDATES, APPLIED, FALLBACK }
    public record SearchResult(List<Hit> hits, RerankStatus rerankStatus, String rerankModel) {}
    private record Selection(List<Candidate> candidates, Mode mode, String scope) {}
    public record Candidate(long chunkId, long documentId, long knowledgeBaseId, int indexVersion, double score) {}
    public record Hit(long chunkId, long documentId, String documentName, String content,
                      Integer pageNumber, int paragraphNumber, double score) {}
}
