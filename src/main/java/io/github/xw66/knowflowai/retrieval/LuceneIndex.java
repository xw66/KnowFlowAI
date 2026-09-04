package io.github.xw66.knowflowai.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@Profile({"api", "worker"})
@ConditionalOnProperty(name="app.bm25.enabled", havingValue="true")
public class LuceneIndex implements AutoCloseable {
    private static final String INSTANCE_KEY="knowflow_instance";
    private final Path path;
    private final CJKAnalyzer analyzer=new CJKAnalyzer();
    private FSDirectory directory;
    private IndexWriter writer;
    private String instanceId;

    public LuceneIndex(@Value("${app.bm25.directory}") String path, Environment environment) throws IOException {
        this.path=Path.of(path).toAbsolutePath().normalize();
        if (!environment.acceptsProfiles(Profiles.of("worker"))) return;
        directory=FSDirectory.open(this.path);
        try {
            writer=new IndexWriter(directory,new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity()).setCommitOnClose(false));
            for (var entry : writer.getLiveCommitData()) {
                if (INSTANCE_KEY.equals(entry.getKey())) instanceId=entry.getValue();
            }
            if (instanceId==null) {
                if (DirectoryReader.indexExists(directory)) throw new IOException("目录包含未知来源的索引");
                instanceId=UUID.randomUUID().toString();
                writer.setLiveCommitData(Map.of(INSTANCE_KEY,instanceId).entrySet());
                writer.commit();
            }
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public String instanceId() {
        if (writer==null) throw new IllegalStateException("API 进程不能写索引");
        return instanceId;
    }

    public synchronized void replace(long documentId, long baseId, int version, List<Chunk> chunks) throws IOException {
        instanceId();
        var documents=new ArrayList<Document>(chunks.size());
        for (var chunk : chunks) {
            var document=new Document();
            document.add(new StringField("document_id",Long.toString(documentId),Field.Store.YES));
            document.add(new StringField("knowledge_base_id",Long.toString(baseId),Field.Store.NO));
            document.add(new StoredField("chunk_id",chunk.id()));
            document.add(new StoredField("index_version",version));
            document.add(new TextField("content",chunk.content(),Field.Store.NO));
            documents.add(document);
        }
        // 同一文档整组替换，重放不会重复；只有 commit 后 API 才能看见新分块。
        writer.updateDocuments(new Term("document_id",Long.toString(documentId)),documents);
        writer.commit();
    }

    public Batch search(long baseId, String text, int limit) throws IOException {
        if (!Files.isDirectory(path)) return new Batch("",List.of());
        // ponytail: 每次打开已提交 Reader 保证跨进程一致性；实测开销显著时换成 SearcherManager 刷新。
        try (var readDirectory=FSDirectory.open(path)) {
            if (!DirectoryReader.indexExists(readDirectory)) return new Batch("",List.of());
            try (var reader=DirectoryReader.open(readDirectory)) {
                String instance=reader.getIndexCommit().getUserData().get(INSTANCE_KEY);
                if (instance==null) throw new IOException("索引实例标识缺失");
                // 限制关键词数量，避免长问题构造过大的布尔查询；不解释用户输入的查询语法。
                var terms=new QueryBuilder(new LimitTokenCountAnalyzer(analyzer,256)).createBooleanQuery("content",text);
                if (terms==null) return new Batch(instance,List.of());
                var query=new BooleanQuery.Builder().add(terms,BooleanClause.Occur.MUST)
                        .add(new TermQuery(new Term("knowledge_base_id",Long.toString(baseId))),BooleanClause.Occur.FILTER).build();
                var searcher=new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                var hits=new ArrayList<SearchService.Candidate>();
                for (var hit : searcher.search(query,limit).scoreDocs) {
                    var stored=searcher.storedFields().document(hit.doc);
                    hits.add(new SearchService.Candidate(stored.getField("chunk_id").numericValue().longValue(),
                            Long.parseLong(stored.get("document_id")),baseId,
                            stored.getField("index_version").numericValue().intValue(),hit.score));
                }
                return new Batch(instance,List.copyOf(hits));
            }
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() throws IOException {
        try { if (writer!=null) writer.close(); }
        finally { try { if (directory!=null) directory.close(); } finally { analyzer.close(); } }
    }

    public record Chunk(long id, String content) {}
    public record Batch(String instanceId, List<SearchService.Candidate> candidates) {}

    public Batch inspectDocument(long baseId, long documentId, int limit) throws IOException {
        if (limit < 1 || limit > 2001) throw new IllegalArgumentException("对账上限无效");
        // 不创建目录；目录或提交丢失与读取失败由调用者分别报告。
        java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
        try (var readDirectory=FSDirectory.open(path)) {
            if (!DirectoryReader.indexExists(readDirectory)) return new Batch("",List.of());
            try (var reader=DirectoryReader.open(readDirectory)) {
                String instance=reader.getIndexCommit().getUserData().get(INSTANCE_KEY);
                if (instance==null) throw new IOException("索引实例标识缺失");
                var query=new BooleanQuery.Builder()
                        .add(new TermQuery(new Term("document_id",Long.toString(documentId))),BooleanClause.Occur.FILTER)
                        .add(new TermQuery(new Term("knowledge_base_id",Long.toString(baseId))),BooleanClause.Occur.FILTER).build();
                var searcher=new IndexSearcher(reader);
                var hits=new ArrayList<SearchService.Candidate>();
                for (var hit:searcher.search(query,limit).scoreDocs) {
                    var stored=searcher.storedFields().document(hit.doc);
                    hits.add(new SearchService.Candidate(stored.getField("chunk_id").numericValue().longValue(),documentId,baseId,
                            stored.getField("index_version").numericValue().intValue(),0));
                }
                return new Batch(instance,List.copyOf(hits));
            }
        }
    }
}
