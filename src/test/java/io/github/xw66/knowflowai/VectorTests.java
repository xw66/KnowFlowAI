package io.github.xw66.knowflowai;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import com.sun.net.httpserver.HttpServer;
import io.github.xw66.knowflowai.ingestion.VectorTaskProcessor;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.web-application-type=servlet", "app.outbox.initial-delay=3600000",
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.embedding.enabled=true", "app.embedding.api-key=test-only", "app.embedding.model=test-embedding",
        "app.embedding.dimensions=3", "app.vector.initial-delay=3600000", "app.processing.initial-delay=3600000",
        "app.cleanup.initial-delay=3600000",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@ActiveProfiles({"worker", "api"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
class VectorTests {
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2").withExposedPorts(6333);
    static HttpServer server;
    static volatile int responseStatus = 200;
    static volatile int responseDimensions = 3;
    static volatile String requestedPath;
    static volatile int calls;
    static volatile Runnable modelHook = () -> {};
    static final ObjectMapper JSON = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path documentDirectory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("app.document.storage-directory", () -> documentDirectory.toString());
        QDRANT.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 仅验证兼容协议与状态机，固定测试向量没有语义能力，不能用于检索评测。
        server.createContext("/", exchange -> {
            calls++;
            modelHook.run();
            requestedPath = exchange.getRequestURI().getPath();
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            var data = new ArrayList<Map<String, Object>>();
            int count = request.path("input").isArray() ? request.path("input").size() : 1;
            for (int i = 0; i < count; i++) {
                var vector = new ArrayList<Double>();
                for (int d = 0; d < responseDimensions; d++) vector.add(0.1 + d);
                data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
            }
            byte[] body = JSON.writeValueAsBytes(responseStatus == 200
                    ? Map.of("object", "list", "model", "test-embedding", "data", data, "usage", Map.of("prompt_tokens", count, "total_tokens", count))
                    : Map.of("error", Map.of("message", "test failure", "type", "server_error")));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        registry.add("app.embedding.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        registry.add("app.qdrant.url", () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
    }

    @AfterAll static void stop() { server.stop(0); QDRANT.stop(); }
    @Autowired JdbcClient jdbc;
    @Autowired VectorTaskProcessor processor;
    @Autowired QdrantIndex index;
    @Autowired io.github.xw66.knowflowai.ingestion.VectorCleanupProcessor cleanup;
    @Autowired io.github.xw66.knowflowai.document.DocumentService documents;
    @Autowired io.github.xw66.knowflowai.ingestion.TextTaskProcessor parser;
    @Autowired io.github.xw66.knowflowai.retrieval.SearchService search;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test
    void deletionDuringEmbeddingFencesCompletionAndRechecksLateWrites() {
        long task=seed(1), user=owner(task), base=base(task);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        modelHook=() -> {
            documents.delete(user,base,document);
            cleanup.processNext();
        };
        try { processor.processNext(); } finally { modelHook=() -> {}; }
        assertThat(state(task)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT status FROM document WHERE id=:id").param("id",document).query(String.class).single()).isEqualTo("DELETED");
        assertThat(count(task)).isEqualTo(1);
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE vector_cleanup SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",document).update();
        cleanup.processNext();
        assertThat(count(task)).isZero();
        assertThat(jdbc.sql("SELECT last_cleaned_at IS NOT NULL FROM vector_cleanup WHERE document_id=:id").param("id",document).query(Boolean.class).single()).isTrue();
    }

    @Test
    void cleanupRetriesOutageAndDoesNotDeleteOtherDocuments() {
        long task=seed(1); processor.processNext();
        long other=seed(1); processor.processNext();
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        documents.delete(owner(task),base(task),document);
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        try { cleanup.processNext(); }
        finally { QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec(); }
        assertThat(jdbc.sql("SELECT error_code FROM vector_cleanup WHERE document_id=:id").param("id",document).query(String.class).single()).isEqualTo("VECTOR_CLEANUP_FAILED");
        assertThat(count(task)).isEqualTo(1);
        jdbc.sql("UPDATE vector_cleanup SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",document).update();
        int before=calls;
        cleanup.processNext();
        assertThat(calls).isEqualTo(before);
        assertThat(count(task)).isZero();
        assertThat(count(other)).isEqualTo(1);
    }

    @Test
    void cleanupUsesAllRecordedCollectionsAndRecoversExpiredLease() {
        long task=seed(1); processor.processNext();
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        var previousIndex=new QdrantIndex("http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333),"",3,"previous-model","http://test.invalid/v1");
        previousIndex.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{0.1f,1.1f,2.1f},
                "payload",Map.of("document_id",document,"knowledge_base_id",base(task)))));
        jdbc.sql("UPDATE document SET vector_collection=:collection WHERE id=:id").param("collection",previousIndex.collection()).param("id",document).update();
        documents.delete(owner(task),base(task),document);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vector_cleanup WHERE document_id=:id").param("id",document).query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("UPDATE vector_cleanup SET lease_token=:token,available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE document_id=:id")
                .param("token",UUID.randomUUID().toString()).param("id",document).update();
        cleanup.processNext(); cleanup.processNext();
        assertThat(count(task)).isZero();
        assertThat(previousIndex.search(new float[]{0.1f,1.1f,2.1f},base(task),10).path("result").path("points")).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vector_cleanup WHERE document_id=:id AND last_cleaned_at IS NOT NULL AND lease_token IS NULL")
                .param("id",document).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void searchEndpointReturnsOnlyAuthorizedActiveChunksAndValidatesRequests() throws Exception {
        long task = seed(1);
        processor.processNext();
        long base = base(task), user = owner(task);
        var result = request(base, user, "{\"query\":\"测试\",\"topK\":5}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JSON.readTree(result.body()).get(0).path("content").asText()).isEqualTo("测试段落0");
        assertThat(request(base, user, "{\"query\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(request(base, user, "{\"query\":\"测试\",\"topK\":21}").statusCode()).isEqualTo(400);
        assertThat(request(base, user, "{\"query\":\"测试\",\"userId\":1}").statusCode()).isEqualTo(400);
        assertThat(request(base, 0, "{\"query\":\"测试\"}").statusCode()).isEqualTo(401);
        long other = seed(1); processor.processNext();
        int before = calls;
        assertThat(request(base, owner(other), "{\"query\":\"测试\"}").statusCode()).isEqualTo(404);
        jdbc.sql("UPDATE app_user SET system_role='ADMIN' WHERE id=:id").param("id",owner(other)).update();
        assertThat(request(base, owner(other), "{\"query\":\"测试\"}").statusCode()).isEqualTo(404);
        assertThat(calls).isEqualTo(before);
        assertThat(index.search(new float[]{0.1f,1.1f,2.1f}, base,200).path("result").path("points"))
                .allMatch(point -> point.path("payload").path("knowledge_base_id").asLong() == base);
    }

    @Test
    void rebuildingFailureKeepsOldSearchAndSuccessfulVersionSwitchesAtomically() {
        long task=seed(1); processor.processNext();
        long user=owner(task), base=base(task);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        var retry=documents.reindex(user,base,document,UUID.randomUUID().toString());
        assertThat(search.search(user,base,"测试",5)).hasSize(1);
        jdbc.sql("UPDATE document_task SET stage='QUEUED',received_at=CURRENT_TIMESTAMP(6),attempts=2 WHERE id=:id").param("id",retry.taskId()).update();
        parser.processNext();
        assertThat(state(retry.taskId())).isEqualTo("FAILED");
        assertThat(search.search(user,base,"测试",5)).hasSize(1);
        var next=documents.reindex(user,base,document,UUID.randomUUID().toString());
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,3,0,1,'新版本正文')").param("id",document).update();
        jdbc.sql("UPDATE document_task SET stage='CHUNKED',received_at=CURRENT_TIMESTAMP(6) WHERE id=:id").param("id",next.taskId()).update();
        assertThat(search.search(user,base,"测试",5)).extracting(io.github.xw66.knowflowai.retrieval.SearchService.Hit::content).containsExactly("测试段落0");
        processor.processNext();
        assertThat(state(next.taskId())).isEqualTo("SUCCEEDED");
        assertThat(active(next.taskId())).isEqualTo(3);
        assertThat(search.search(user,base,"测试",5)).extracting(io.github.xw66.knowflowai.retrieval.SearchService.Hit::content).containsExactly("新版本正文");
    }

    @Test
    void staleFailedWrongCollectionAndForgedCrossBaseCandidatesAreExcluded() {
        long task=seed(1); processor.processNext();
        long user=owner(task), base=base(task);
        for (String status : java.util.List.of("FAILED","DELETED","PROCESSING")) {
            jdbc.sql("UPDATE document SET status=:status WHERE knowledge_base_id=:base").param("status",status).param("base",base).update();
            assertThat(search.search(user,base,"测试",5)).isEmpty();
        }
        jdbc.sql("UPDATE document SET status='READY', active_index_version=2 WHERE knowledge_base_id=:base").param("base",base).update();
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE document SET active_index_version=1, vector_collection='wrong' WHERE knowledge_base_id=:base").param("base",base).update();
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE document SET vector_collection=:collection WHERE knowledge_base_id=:base").param("collection",index.collection()).param("base",base).update();
        long other=seed(1); processor.processNext();
        var row=jdbc.sql("SELECT c.id,c.document_id FROM document_chunk c JOIN document_task t ON t.document_id=c.document_id WHERE t.id=:id").param("id",other).query().singleRow();
        index.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{0.1f,1.1f,2.1f},
                "payload",Map.of("knowledge_base_id",base,"document_id",row.get("document_id"),"chunk_id",row.get("id"),"index_version",1))));
        assertThat(search.search(user,base,"测试",5)).hasSize(1).allMatch(hit -> hit.documentId()!=((Number)row.get("document_id")).longValue());
    }

    @Test
    void revocationDuringEmbeddingIsRecheckedAndModelErrorsAreSanitized() throws Exception {
        long task=seed(1); processor.processNext();
        long base=base(task), user=owner(task);
        modelHook=() -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:base AND user_id=:user").param("base",base).param("user",user).update();
        try { assertThat(request(base,user,"{\"query\":\"测试\"}").statusCode()).isEqualTo(404); }
        finally { modelHook=() -> {}; }
        jdbc.sql("INSERT INTO knowledge_member(knowledge_base_id,user_id,role) VALUES (:base,:user,'VIEWER')").param("base",base).param("user",user).update();
        responseStatus=503;
        try {
            var response=request(base,user,"{\"query\":\"测试\"}");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("test-only","test failure","测试段落");
        } finally { responseStatus=200; }
        assertThat(request(base,user,"{\"query\":\"测试\"}").statusCode()).isEqualTo(200);
    }

    private long base(long task) { return jdbc.sql("SELECT d.knowledge_base_id FROM document d JOIN document_task t ON t.document_id=d.id WHERE t.id=:id").param("id",task).query(Long.class).single(); }
    private long owner(long task) { return jdbc.sql("SELECT owner_id FROM knowledge_base WHERE id=:id").param("id",base(task)).query(Long.class).single(); }
    private java.net.http.HttpResponse<String> request(long base,long user,String body) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/knowledge-bases/"+base+"/search"))
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (user>0) {
            var now=java.time.Instant.now();
            var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("knowflow-ai").audience(java.util.List.of("knowflow-api"))
                    .subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
            String token=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
            request.header("Authorization","Bearer "+token);
        }
        try(var client=java.net.http.HttpClient.newHttpClient()) { return client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString()); }
    }

    @Test
    void batchesActivateOnlyWhenCompleteAndReplayDoesNotDuplicatePoints() {
        long task = seed(17);
        processor.processNext();
        assertThat(state(task)).isEqualTo("PENDING");
        assertThat(active(task)).isNull();
        assertThat(count(task)).isEqualTo(16);
        processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
        assertThat(active(task)).isEqualTo(1);
        assertThat(count(task)).isEqualTo(17);
        assertThat(requestedPath).isEqualTo("/v1/embeddings");
        jdbc.sql("UPDATE document_task SET status = 'PROCESSING', stage = 'INDEXING', vector_cursor = -1, lease_token = 'crashed', lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id = :id")
                .param("id", task).update();
        processor.processNext(); processor.processNext();
        assertThat(count(task)).isEqualTo(17);
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void modelFailureAndWrongDimensionsNeverActivateAndRecoveryResumes() {
        long task = seed(1);
        responseStatus = 503;
        int before = calls;
        try { processor.processNext(); } finally { responseStatus = 200; }
        assertThat(calls - before).isEqualTo(1);
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        due(task);
        responseDimensions = 2;
        try { processor.processNext(); } finally { responseDimensions = 3; }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void repeatedModelFailureStopsAtBudget() {
        long task = seed(1);
        responseStatus = 429;
        try {
            for (int i = 0; i < 3; i++) { due(task); processor.processNext(); }
        } finally { responseStatus = 200; }
        assertThat(state(task)).isEqualTo("FAILED");
        assertThat(active(task)).isNull();
    }

    @Test
    void qdrantFailureDoesNotAdvanceCursorAndRecovers() {
        long task = seed(1);
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        try { processor.processNext(); }
        finally { QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec(); }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        assertThat(jdbc.sql("SELECT vector_cursor FROM document_task WHERE id=:id").param("id", task).query(Integer.class).single()).isEqualTo(-1);
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void mysqlCommitFailureAfterQdrantWriteCanReplayWithoutDuplicate() {
        long task = seed(1);
        long documentId = jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id", task).query(Long.class).single();
        jdbc.sql("ALTER TABLE document_task ADD CONSTRAINT reject_vector_completion CHECK (document_id <> "
                + documentId + " OR status <> 'SUCCEEDED')").update();
        try { processor.processNext(); }
        finally { jdbc.sql("ALTER TABLE document_task DROP CHECK reject_vector_completion").update(); }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        assertThat(count(task)).isEqualTo(1);
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
        assertThat(count(task)).isEqualTo(1);
    }

    private void due(long id) { jdbc.sql("UPDATE document_task SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=:id").param("id", id).update(); }
    private String state(long id) { return jdbc.sql("SELECT status FROM document_task WHERE id=:id").param("id", id).query(String.class).single(); }
    private Integer active(long id) { return (Integer) jdbc.sql("SELECT d.active_index_version AS version FROM document d JOIN document_task t ON d.id=t.document_id WHERE t.id=:id").param("id",id).query().singleRow().get("version"); }
    private long count(long id) {
        long documentId = jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",id).query(Long.class).single();
        return RestClient.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333))
                .post().uri("/collections/" + index.collection() + "/points/count")
                .body(Map.of("exact", true, "filter", Map.of("must", java.util.List.of(Map.of("key", "document_id", "match", Map.of("value", documentId))))))
                .retrieve().body(JsonNode.class).path("result").path("count").asLong();
    }
    private long seed(int count) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO app_user(username,password_hash) VALUES (:name,'unused')").param("name",unique).update();
        long user = jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO knowledge_base(name,owner_id) VALUES (:name,:owner)").param("name",unique).param("owner",user).update();
        long kb = jdbc.sql("SELECT id FROM knowledge_base WHERE name=:name").param("name",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO knowledge_member(knowledge_base_id,user_id,role) VALUES (:base,:user,'OWNER')").param("base",kb).param("user",user).update();
        jdbc.sql("INSERT INTO document(knowledge_base_id,uploaded_by,name,storage_key,sha256,media_type,size_bytes,idempotency_key,status) VALUES (:kb,:user,'test.txt',:key,:hash,'text/plain',1,:key,'PROCESSING')")
                .param("kb",kb).param("user",user).param("key",unique).param("hash","0".repeat(64)).update();
        long document = jdbc.sql("SELECT id FROM document WHERE storage_key=:key").param("key",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO document_task(document_id,stage,received_at) VALUES (:id,'CHUNKED',CURRENT_TIMESTAMP(6))").param("id",document).update();
        for (int i=0;i<count;i++) jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,1,:position,:paragraph,:content)")
                .param("id",document).param("position",i).param("paragraph",i+1).param("content","测试段落"+i).update();
        return jdbc.sql("SELECT id FROM document_task WHERE document_id=:id").param("id",document).query(Long.class).single();
    }
}
