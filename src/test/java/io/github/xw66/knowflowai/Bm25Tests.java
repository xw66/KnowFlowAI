package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.xw66.knowflowai.document.DocumentService;
import io.github.xw66.knowflowai.ingestion.Bm25TaskProcessor;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import io.github.xw66.knowflowai.retrieval.LuceneIndex;
import io.github.xw66.knowflowai.retrieval.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.main.web-application-type=servlet", "app.bm25.enabled=true", "app.embedding.enabled=false",
        "app.rerank.enabled=false",
        "app.chat.enabled=false",
        "app.cache.enabled=false",
        "app.rate-limit.enabled=false", "management.health.redis.enabled=false",
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.bm25.initial-delay=3600000", "app.outbox.initial-delay=3600000", "app.processing.initial-delay=3600000",
        "app.cleanup.initial-delay=3600000", "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@ActiveProfiles({"api","worker"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
class Bm25Tests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.bm25.directory",()->directory.resolve("shared").toString());
    }
    @Autowired JdbcClient jdbc;
    @Autowired LuceneIndex index;
    @Autowired Bm25TaskProcessor processor;
    @Autowired SearchService search;
    @Autowired io.github.xw66.knowflowai.chat.AnswerService answers;
    @Autowired DocumentService documents;
    @Autowired KnowledgeBaseService bases;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtEncoder encoder;
    @LocalServerPort int port;

    @BeforeEach void clearTestDatabase() {
        for (String table : List.of("outbox_event","vector_cleanup","bm25_index_progress","document_chunk","document_task","document")) {
            jdbc.sql("DELETE FROM "+table).update();
        }
    }

    @Test void chineseSearchReturnsReferencesAndFiltersKnowledgeBase() throws Exception {
        var first=seed("员工报销流程需要提交发票。");
        assertThatThrownBy(()->answers.answer(first.user(),first.base(),"报销",4,SearchService.Mode.BM25,false))
                .isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(503));
        var second=seed("保密报销流程需要提交合同。");
        processor.processNext(); processor.processNext();
        var hits=search.search(first.user(),first.base(),"报销流程",5,SearchService.Mode.BM25);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().documentId()).isEqualTo(first.document());
        assertThat(hits.getFirst().content()).isEqualTo("员工报销流程需要提交发票。");
        assertThat(hits.getFirst().documentName()).isEqualTo("中文制度.md");
        assertThat(hits.getFirst().paragraphNumber()).isEqualTo(1);
        assertThat(hits.getFirst().score()).isPositive();
        assertThat(index.search(first.base(),"报销",200).candidates()).allMatch(hit -> hit.documentId()!=second.document());
        var response=request(first.user(),first.base(),"{\"query\":\"报销\",\"mode\":\"BM25\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(request(first.user(),first.base(),"{\"query\":\"报销\",\"mode\":\"INVALID\"}").statusCode()).isEqualTo(400);
        assertThat(request(0,first.base(),"{\"query\":\"报销\",\"mode\":\"BM25\"}").statusCode()).isEqualTo(401);
        assertThat(request(second.user(),first.base(),"{\"query\":\"报销\",\"mode\":\"BM25\"}").statusCode()).isEqualTo(404);
        assertThat(request(first.user(),first.base(),"{\"query\":\"报销\"}").statusCode()).isEqualTo(503);
        assertThat(request(first.user(),first.base(),"{\"query\":\"报销\",\"mode\":\"HYBRID\"}").statusCode()).isEqualTo(503);
    }

    @Test void luceneCommitAloneCannotPublishContentAndReplayDoesNotDuplicate() throws Exception {
        var data=seed("报销流程");
        index.replace(data.document(),data.base(),1,chunks(data.document(),1));
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(1);
        assertThat(hits(data,"报销")).isEmpty();
        processor.processNext();
        assertThat(hits(data,"报销")).hasSize(1);
        jdbc.sql("DELETE FROM bm25_index_progress WHERE document_id=:id").param("id",data.document()).update();
        processor.processNext(); processor.processNext();
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(1);
    }

    @Test void activationExcludesOldVersionBeforeNewBm25Commit() {
        var data=seed("旧版报销制度"); processor.processNext();
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,2,0,2,'新版请假制度')")
                .param("id",data.document()).update();
        jdbc.sql("UPDATE document SET index_version=2,active_index_version=2 WHERE id=:id").param("id",data.document()).update();
        assertThat(hits(data,"报销")).isEmpty();
        assertThat(hits(data,"请假")).isEmpty();
        processor.processNext();
        assertThat(hits(data,"请假")).hasSize(1).allMatch(hit -> hit.paragraphNumber()==2);
        assertThat(hits(data,"报销")).isEmpty();
        jdbc.sql("UPDATE document SET index_version=3 WHERE id=:id").param("id",data.document()).update();
        processor.processNext();
        assertThat(hits(data,"请假")).hasSize(1);
    }

    @Test void revocationAndDeletionPreventContentBeforePhysicalCleanup() throws Exception {
        var data=seed("报销制度"); processor.processNext();
        jdbc.sql("UPDATE app_user SET status='DISABLED' WHERE id=:id").param("id",data.user()).update();
        assertThatThrownBy(()->hits(data,"报销")).isInstanceOf(ResponseStatusException.class);
        jdbc.sql("UPDATE app_user SET status='ACTIVE' WHERE id=:id").param("id",data.user()).update();
        jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",data.base()).update();
        assertThatThrownBy(()->hits(data,"报销")).isInstanceOf(ResponseStatusException.class);
        jdbc.sql("INSERT INTO knowledge_member(knowledge_base_id,user_id,role) VALUES (:base,:user,'OWNER')")
                .param("base",data.base()).param("user",data.user()).update();
        documents.delete(data.user(),data.base(),data.document());
        assertThat(hits(data,"报销")).isEmpty();
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(1);
        processor.processNext();
        assertThat(index.search(data.base(),"报销",200).candidates()).isEmpty();
        assertThat(progress(data)).isEqualTo("CLEANED");
    }

    @Test void databaseCompletionFailureIsRetryableWithoutPrematureVisibility() throws Exception {
        var data=seed("报销制度");
        jdbc.sql("ALTER TABLE bm25_index_progress ADD CONSTRAINT test_bm25_commit CHECK (status<>'READY')").update();
        try { processor.processNext(); }
        finally { jdbc.sql("ALTER TABLE bm25_index_progress DROP CHECK test_bm25_commit").update(); }
        assertThat(progress(data)).isEqualTo("RETRY_WAIT");
        assertThat(hits(data,"报销")).isEmpty();
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(1);
        jdbc.sql("UPDATE bm25_index_progress SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",data.document()).update();
        processor.processNext();
        assertThat(progress(data)).isEqualTo("READY");
        assertThat(hits(data,"报销")).hasSize(1);
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(1);
    }

    @Test void missingChunksRetryThenRecover() {
        var data=seed("报销制度");
        jdbc.sql("DELETE FROM document_chunk WHERE document_id=:id").param("id",data.document()).update();
        processor.processNext();
        assertThat(progress(data)).isEqualTo("RETRY_WAIT");
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,1,0,1,'报销制度')")
                .param("id",data.document()).update();
        jdbc.sql("UPDATE bm25_index_progress SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",data.document()).update();
        processor.processNext();
        assertThat(hits(data,"报销")).hasSize(1);
    }

    @Test void newIndexInstanceRebuildsFromMysqlAndRejectsOldProgress() throws Exception {
        var data=seed("报销制度"); processor.processNext();
        String oldInstance=index.instanceId();
        Path replacement=directory.resolve("recovery-"+UUID.randomUUID());
        try (var writer=localIndex(replacement,true); var reader=localIndex(replacement,false)) {
            assertThat(writer.instanceId()).isNotEqualTo(oldInstance);
            var recoveredSearch=service(reader);
            assertThat(recoveredSearch.search(data.user(),data.base(),"报销",5,SearchService.Mode.BM25)).isEmpty();
            writer.replace(data.document(),data.base(),1,chunks(data.document(),1));
            assertThat(reader.search(data.base(),"报销",200).candidates()).hasSize(1);
            assertThat(recoveredSearch.search(data.user(),data.base(),"报销",5,SearchService.Mode.BM25)).isEmpty();
            new Bm25TaskProcessor(jdbc,writer).processNext();
            assertThat(recoveredSearch.search(data.user(),data.base(),"报销",5,SearchService.Mode.BM25)).hasSize(1);
        }
        try (var restarted=localIndex(replacement,true); var reader=localIndex(replacement,false)) {
            assertThat(restarted.instanceId()).isEqualTo(jdbc.sql("SELECT instance_id FROM bm25_index_progress WHERE document_id=:id")
                    .param("id",data.document()).query(String.class).single());
            assertThat(service(reader).search(data.user(),data.base(),"报销",5,SearchService.Mode.BM25)).hasSize(1);
        }
    }

    @Test void sharedDirectoryAllowsMultipleReadersButOnlyOneWriter() throws Exception {
        Path shared=directory.resolve("lock-"+UUID.randomUUID());
        try (var writer=localIndex(shared,true); var reader=localIndex(shared,false)) {
            assertThatThrownBy(()->localIndex(shared,true)).isInstanceOf(org.apache.lucene.store.LockObtainFailedException.class);
            assertThatThrownBy(()->reader.replace(1,1,1,List.of())).isInstanceOf(IllegalStateException.class);
            writer.replace(1,1,1,List.of(new LuceneIndex.Chunk(1,"报销制度")));
            assertThat(reader.search(1,"报销",200).candidates()).hasSize(1);
            writer.replace(1,1,2,List.of(new LuceneIndex.Chunk(2,"请假制度")));
            assertThat(reader.search(1,"报销",200).candidates()).isEmpty();
            assertThat(reader.search(1,"请假",200).candidates()).hasSize(1);
        }
    }

    @Test void punctuationEmptyTermsAndLongQueriesStayBounded() {
        var data=seed("报销制度"); processor.processNext();
        assertThat(hits(data,"!!!")).isEmpty();
        assertThat(hits(data,"报销".repeat(900))).hasSize(1);
    }

    @Test void finalAuthorizationRejectsForgedCandidatesAndRevocationDuringRetrieval() throws Exception {
        var data=seed("报销制度");
        var other=seed("保密报销");
        processor.processNext(); processor.processNext();
        index.replace(other.document(),data.base(),1,chunks(other.document(),1));
        assertThat(index.search(data.base(),"报销",200).candidates()).hasSize(2);
        assertThat(hits(data,"报销")).hasSize(1).allMatch(hit -> hit.documentId()==data.document());
        var environment=new MockEnvironment(); environment.setActiveProfiles("api");
        try (var reader=new LuceneIndex(directory.resolve("shared").toString(),environment) {
            @Override public Batch search(long base, String query, int limit) throws java.io.IOException {
                var result=super.search(base,query,limit);
                jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base).update();
                return result;
            }
        }) {
            assertThatThrownBy(()->service(reader).search(data.user(),data.base(),"报销",5,SearchService.Mode.BM25))
                    .isInstanceOfSatisfying(ResponseStatusException.class,exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        }
    }

    @Test void apiReaderCannotSeeUncommittedLuceneChanges() throws Exception {
        Path shared=directory.resolve("commit-"+UUID.randomUUID());
        try (var disk=org.apache.lucene.store.FSDirectory.open(shared);
             var analyzer=new org.apache.lucene.analysis.cjk.CJKAnalyzer();
             var writer=new org.apache.lucene.index.IndexWriter(disk,new org.apache.lucene.index.IndexWriterConfig(analyzer));
             var reader=localIndex(shared,false)) {
            writer.setLiveCommitData(Map.of("knowflow_instance",UUID.randomUUID().toString()).entrySet()); writer.commit();
            var document=new org.apache.lucene.document.Document();
            document.add(new org.apache.lucene.document.StringField("document_id","1",org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField("knowledge_base_id","1",org.apache.lucene.document.Field.Store.NO));
            document.add(new org.apache.lucene.document.StoredField("chunk_id",1L));
            document.add(new org.apache.lucene.document.StoredField("index_version",1));
            document.add(new org.apache.lucene.document.TextField("content","报销制度",org.apache.lucene.document.Field.Store.NO));
            writer.addDocument(document); writer.flush();
            assertThat(reader.search(1,"报销",200).candidates()).isEmpty();
            writer.commit();
            assertThat(reader.search(1,"报销",200).candidates()).hasSize(1);
        }
    }

    private LuceneIndex localIndex(Path path, boolean worker) throws Exception {
        var environment=new MockEnvironment(); environment.setActiveProfiles(worker ? "worker" : "api");
        return new LuceneIndex(path.toString(),environment);
    }
    private SearchService service(LuceneIndex reader) {
        var factory=new StaticListableBeanFactory(Map.of("reader",reader));
        return new SearchService(jdbc,bases,factory.getBeanProvider(EmbeddingModel.class),factory.getBeanProvider(QdrantIndex.class),
                factory.getBeanProvider(LuceneIndex.class),factory.getBeanProvider(io.github.xw66.knowflowai.retrieval.RerankClient.class),manager);
    }
    private List<LuceneIndex.Chunk> chunks(long document, int version) {
        return jdbc.sql("SELECT id,content FROM document_chunk WHERE document_id=:id AND index_version=:version ORDER BY chunk_index")
                .param("id",document).param("version",version).query(LuceneIndex.Chunk.class).list();
    }
    private List<SearchService.Hit> hits(Data data, String query) {
        return search.search(data.user(),data.base(),query,5,SearchService.Mode.BM25);
    }
    private String progress(Data data) {
        return jdbc.sql("SELECT status FROM bm25_index_progress WHERE document_id=:id").param("id",data.document()).query(String.class).single();
    }
    private Data seed(String content) {
        String unique=UUID.randomUUID().toString().replace("-","");
        var holder=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO app_user(username,password_hash) VALUES (:name,'test-only')").param("name","bm_"+unique).update(holder);
        long user=holder.getKey().longValue();
        long base=bases.create(user,"BM25 测试库").id();
        holder=new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO document(knowledge_base_id,uploaded_by,name,storage_key,sha256,media_type,size_bytes,idempotency_key,status,active_index_version)
                VALUES (:base,:user,'中文制度.md',:key,:hash,'text/markdown',100,:key,'READY',1)
                """).param("base",base).param("user",user).param("key",unique).param("hash","0".repeat(64)).update(holder);
        long document=holder.getKey().longValue();
        jdbc.sql("INSERT INTO document_task(document_id,status,stage) VALUES (:id,'SUCCEEDED','INDEXED')").param("id",document).update();
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,1,0,1,:content)")
                .param("id",document).param("content",content).update();
        return new Data(user,base,document);
    }
    private HttpResponse<String> request(long user, long base, String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/knowledge-bases/"+base+"/search"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (user!=0) {
            var claims=JwtClaimsSet.builder().issuer("knowflow-ai").audience(List.of("knowflow-api")).subject(Long.toString(user))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            builder.header("Authorization","Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue());
        }
        try (var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private record Data(long user,long base,long document) {}
}
