package io.github.xw66.knowflowai;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.cache.enabled=true",
        "app.idempotency.enabled=true",
        "app.rate-limit.enabled=false",
        "app.document.max-file-size=64KB", "spring.servlet.multipart.max-request-size=128KB"})
@Import({KnowFlowAiApplicationTests.DatabaseConfiguration.class, KnowledgeBaseTests.RedisConfiguration.class})
@ActiveProfiles("test")
class DocumentTests {
    @TempDir
    static Path storageDirectory;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.document.storage-directory", () -> storageDirectory.toString());
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired
    private io.github.xw66.knowflowai.document.DocumentService documents;
    @Autowired
    private io.github.xw66.knowflowai.document.TaskCache taskCache;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private org.testcontainers.containers.GenericContainer<?> redisContainer;
    @Autowired
    private io.github.xw66.knowflowai.document.RequestIdempotency idempotency;

    private Actor owner;
    private long baseId;

    @Test
    void taskCacheHasShortTtlAndRechecksMembershipAndDeletion() throws Exception {
        var uploaded = upload(owner, baseId, UUID.randomUUID().toString(), "cached.txt", fixture("txt"));
        expect(uploaded, 202);
        long task = number(uploaded, "$.taskId");
        var first = documents.task(owner.id(), task);
        assertThat(taskCache.get(task, 1)).isEqualTo(first);
        assertThat(redis.getExpire(taskKey(task, 1), TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
        var viewer = actor(); grant(viewer, "VIEWER");
        assertThat(documents.task(viewer.id(), task)).isEqualTo(first);
        expect(request(owner, "DELETE", "/api/knowledge-bases/" + baseId + "/members/" + viewer.id(), null), 204);
        expect(request(viewer, "GET", "/api/document-tasks/" + task, null), 404);
        expect(request(actor(), "GET", "/api/document-tasks/" + task, null), 404);
        documents.delete(owner.id(), baseId, number(uploaded, "$.documentId"));
        assertThat(redis.hasKey(taskKey(task, 1))).isFalse();
        expect(request(owner, "GET", "/api/document-tasks/" + task, null), 404);
    }

    @Test
    void malformedAndExpiredTaskCacheRefillsWithoutChangingResponse() throws Exception {
        var uploaded = upload(owner, baseId, UUID.randomUUID().toString(), "cache-format.txt", fixture("txt"));
        expect(uploaded, 202);
        long task = number(uploaded, "$.taskId");
        var first = documents.task(owner.id(), task);
        redis.opsForValue().set(taskKey(task, 1), "broken-json", Duration.ofSeconds(5));
        assertThat(documents.task(owner.id(), task)).isEqualTo(first);
        assertThat(taskCache.get(task, 1)).isEqualTo(first);
        redis.expire(taskKey(task, 1), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(taskKey(task, 1))));
        assertThat(documents.task(owner.id(), task)).isEqualTo(first);
        assertThat(taskCache.get(task, 1)).isEqualTo(first);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void terminalTaskAlwaysComesFromDatabaseEvenIfOldCacheRemains(String terminal) throws Exception {
        var uploaded = upload(owner, baseId, UUID.randomUUID().toString(), "terminal.txt", fixture("txt"));
        expect(uploaded, 202);
        long task = number(uploaded, "$.taskId");
        documents.task(owner.id(), task);
        // 模拟外部维护未清缓存的情况，终态查询仍必须以数据库为准。
        jdbc.sql("UPDATE document_task SET status=:status WHERE id=:id").param("status", terminal).param("id", task).update();
        assertThat(taskCache.get(task, 1).status()).isEqualTo("PENDING");
        assertThat(documents.task(owner.id(), task).status()).isEqualTo(terminal);
    }

    @Test
    void rollbackDoesNotPublishTaskStateAndLateFillCannotOverrideCommittedVersion() throws Exception {
        var uploaded = upload(owner, baseId, UUID.randomUUID().toString(), "transaction.txt", fixture("txt"));
        expect(uploaded, 202);
        long task = number(uploaded, "$.taskId");
        var first = documents.task(owner.id(), task);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("UPDATE document_task SET cache_version=cache_version+1, stage='QUEUED' WHERE id=:id").param("id", task).update();
            taskCache.invalidateAfterChange(task);
            assertThat(documents.task(owner.id(), task).stage()).isEqualTo("QUEUED");
            assertThat(redis.hasKey(taskKey(task, 2))).isFalse();
            status.setRollbackOnly();
        });
        assertThat(documents.task(owner.id(), task)).isEqualTo(first);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("UPDATE document_task SET cache_version=cache_version+1, stage='QUEUED' WHERE id=:id").param("id", task).update();
            taskCache.invalidateAfterChange(task);
            assertThat(redis.hasKey(taskKey(task, 1))).isTrue();
        });
        assertThat(redis.hasKey(taskKey(task, 1))).isFalse();
        taskCache.put(task, 1, first);
        assertThat(documents.task(owner.id(), task).stage()).isEqualTo("QUEUED");
        assertThat(taskCache.get(task, 2).stage()).isEqualTo("QUEUED");
    }

    private static String taskKey(long id, long version) {
        return "knowflow:task:v1:" + id + ":" + version;
    }

    @Test
    void redisFailureDoesNotPreventTaskReadOrDocumentDeletion() throws Exception {
        var uploaded = upload(owner, baseId, UUID.randomUUID().toString(), "redis-failure.txt", fixture("txt"));
        expect(uploaded, 202);
        long task = number(uploaded, "$.taskId");
        var initial = documents.task(owner.id(), task);
        redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-get", "-set", "-del");
        try {
            assertThat(documents.task(owner.id(), task)).isEqualTo(initial);
            documents.delete(owner.id(), baseId, number(uploaded, "$.documentId"));
            expect(request(owner, "GET", "/api/document-tasks/" + task, null), 404);
        } finally {
            redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+get", "+set", "+del");
        }
        assertThat(taskCache.get(task, 1)).isEqualTo(initial);
        expect(request(owner, "GET", "/api/document-tasks/" + task, null), 404);
    }

    @Test
    void deletingIsAuthorizedIdempotentAndCannotBeReplayedAsUpload() throws Exception {
        long initialFiles=fileCount();
        String key=UUID.randomUUID().toString();
        var uploaded=upload(owner,baseId,key,"delete.txt",fixture("txt"));
        expect(uploaded,202);
        long id=number(uploaded,"$.documentId"), task=number(uploaded,"$.taskId");
        String path="/api/knowledge-bases/"+baseId+"/documents/"+id;
        var viewer=actor(); grant(viewer,"VIEWER");
        expect(request(null,"DELETE",path,null),401);
        expect(request(viewer,"DELETE",path,null),403);
        expect(request(actor(),"DELETE",path,null),404);
        var editor=actor(); grant(editor,"EDITOR");
        expect(request(editor,"DELETE",path,null),204);
        expect(request(owner,"DELETE",path,null),204);
        expect(request(owner,"GET",path,null),404);
        expect(request(owner,"GET","/api/document-tasks/"+task,null),404);
        expect(reindex(owner,id,UUID.randomUUID().toString()),404);
        expect(upload(owner,baseId,key,"delete.txt",fixture("txt")),409);
        assertThat(jdbc.sql("SELECT error_code FROM document_task WHERE id=:id").param("id",task).query(String.class).single()).isEqualTo("DOCUMENT_DELETED");
        assertThat(fileCount()).isEqualTo(initialFiles+1);
        assertThat(request(owner,"GET","/api/knowledge-bases/"+baseId+"/documents",null).body()).isEqualTo("[]");
    }

    @Test
    void deletionRollsBackWhenCleanupCannotBeRecorded() throws Exception {
        var uploaded=upload(owner,baseId,UUID.randomUUID().toString(),"rollback.txt",fixture("txt"));
        expect(uploaded,202);
        long id=number(uploaded,"$.documentId");
        long task = number(uploaded, "$.taskId");
        var initial = documents.task(owner.id(), task);
        jdbc.sql("RENAME TABLE vector_cleanup TO vector_cleanup_unavailable").update();
        try {
            expect(request(owner,"DELETE","/api/knowledge-bases/"+baseId+"/documents/"+id,null),503);
            assertThat(jdbc.sql("SELECT status FROM document WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("PENDING");
            assertThat(jdbc.sql("SELECT status FROM document_task WHERE document_id=:id").param("id",id).query(String.class).single()).isEqualTo("PENDING");
            assertThat(taskCache.get(task, 1)).isEqualTo(initial);
            assertThat(documents.task(owner.id(), task)).isEqualTo(initial);
        } finally {
            jdbc.sql("RENAME TABLE vector_cleanup_unavailable TO vector_cleanup").update();
        }
    }

    @BeforeEach
    void setup() throws Exception {
        owner = actor();
        baseId = createBase(owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx", "md", "txt"})
    void uploadsSupportedFilesAndCreatesPendingTaskAndOutbox(String extension) throws Exception {
        byte[] bytes = fixture(extension);
        var response = upload(owner, baseId, UUID.randomUUID().toString(), "source." + extension, bytes);
        expect(response, 202);
        long documentId = number(response, "$.documentId");
        long taskId = number(response, "$.taskId");
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/document-tasks/" + taskId);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("PENDING");
        var document = jdbc.sql("SELECT storage_key, sha256, size_bytes, media_type, status FROM document WHERE id = :id")
                .param("id", documentId).query().singleRow();
        assertThat(document.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(document.get("size_bytes")).isEqualTo((long) bytes.length);
        assertThat(document.get("status")).isEqualTo("PENDING");
        assertThat(Files.readAllBytes(storageDirectory.resolve((String) document.get("storage_key")))).isEqualTo(bytes);
        assertThat((String) document.get("storage_key")).doesNotContain("source");
        var outbox = jdbc.sql("SELECT status, published_at, payload FROM outbox_event WHERE task_id = :id")
                .param("id", taskId).query().singleRow();
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(outbox.get("published_at")).isNull();
        assertThat(((Number) JsonPath.read(outbox.get("payload").toString(), "$.taskId")).longValue()).isEqualTo(taskId);
        var task = request(owner, "GET", "/api/document-tasks/" + taskId, null);
        expect(task, 200);
        assertThat(number(task, "$.attempts")).isZero();
        assertThat(task.body()).doesNotContain("storageKey", "password", "sha256");
    }

    @Test
    void retryReturnsSameTaskAndDifferentContentOrNameConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] bytes = fixture("txt");
        var first = upload(owner, baseId, key, "notes.txt", bytes);
        expect(first, 202);
        long files = fileCount();
        var retry = upload(owner, baseId, key, "notes.txt", bytes);
        expect(retry, 202);
        assertThat(retry.body()).isEqualTo(first.body());
        expect(upload(owner, baseId, key, "notes.txt", "不同内容".getBytes(StandardCharsets.UTF_8)), 409);
        expect(upload(owner, baseId, key, "renamed.txt", bytes), 409);
        assertThat(fileCount()).isEqualTo(files);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE task_id = :id")
                .param("id", number(first, "$.taskId")).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void catalogPaginatesAndShowsCurrentTaskWithoutStorageDetails() throws Exception {
        String path = "/api/knowledge-bases/" + baseId + "/documents";
        var empty = request(owner,"GET",path,null);
        expect(empty,200);
        assertThat(mapper.readTree(empty.body())).isEmpty();
        var first = upload(owner,baseId,UUID.randomUUID().toString(),"first.txt",fixture("txt"));
        var second = upload(owner,baseId,UUID.randomUUID().toString(),"second.txt",fixture("txt"));
        long firstId=number(first,"$.documentId"), secondId=number(second,"$.documentId");
        var page=request(owner,"GET",path+"?limit=1",null);
        expect(page,200);
        assertThat(mapper.readTree(page.body()).size()).isEqualTo(1);
        assertThat(number(page,"$[0].id")).isEqualTo(firstId);
        var next=request(owner,"GET",path+"?afterId="+firstId+"&limit=1",null);
        assertThat(number(next,"$[0].id")).isEqualTo(secondId);
        var detail=request(owner,"GET",path+"/"+firstId,null);
        expect(detail,200);
        assertThat(number(detail,"$.latestTaskId")).isEqualTo(number(first,"$.taskId"));
        assertThat(number(detail,"$.indexVersion")).isEqualTo(1);
        assertThat(mapper.readTree(detail.body()).path("activeIndexVersion").isNull()).isTrue();
        assertThat(detail.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(detail.body()).doesNotContain("storage_key","storageKey","sha256","content","vectorCollection");
        jdbc.sql("UPDATE document_task SET status='FAILED',error_code='INVALID_CONTENT' WHERE id=:id").param("id",number(first,"$.taskId")).update();
        var failed=request(owner,"GET",path+"/"+firstId,null);
        assertThat(mapper.readTree(failed.body()).path("latestTaskStatus").asText()).isEqualTo("FAILED");
        expect(request(owner,"GET",path+"?limit=101",null),400);
        expect(request(owner,"GET",path+"?afterId=-1",null),400);
    }

    @Test
    void catalogEnforcesMembershipRevocationAndDeletedVisibility() throws Exception {
        var uploaded=upload(owner,baseId,UUID.randomUUID().toString(),"notes.txt",fixture("txt"));
        long documentId=number(uploaded,"$.documentId");
        String path="/api/knowledge-bases/"+baseId+"/documents";
        var viewer=actor();
        expect(request(viewer,"GET",path,null),404);
        expect(request(viewer,"GET",path+"/"+documentId,null),404);
        grant(viewer,"VIEWER");
        expect(request(viewer,"GET",path+"/"+documentId,null),200);
        expect(request(owner,"DELETE","/api/knowledge-bases/"+baseId+"/members/"+viewer.id(),null),204);
        expect(request(viewer,"GET",path+"/"+documentId,null),404);
        jdbc.sql("UPDATE app_user SET system_role='ADMIN' WHERE id=:id").param("id",viewer.id()).update();
        expect(request(viewer,"GET",path,null),200);
        expect(request(null,"GET",path,null),401);
        long otherBase=createBase(owner);
        expect(request(owner,"GET","/api/knowledge-bases/"+otherBase+"/documents/"+documentId,null),404);
        jdbc.sql("UPDATE document SET status='DELETED' WHERE id=:id").param("id",documentId).update();
        expect(request(owner,"GET",path+"/"+documentId,null),404);
        assertThat(mapper.readTree(request(owner,"GET",path,null).body())).isEmpty();
    }

    @Test
    void reindexRequiresEditAccessAndReusesRequestWithoutReplacingActiveVersion() throws Exception {
        var uploaded=upload(owner,baseId,UUID.randomUUID().toString(),"notes.txt",fixture("txt"));
        long documentId=number(uploaded,"$.documentId"), taskId=number(uploaded,"$.taskId");
        expect(reindex(owner,documentId,UUID.randomUUID().toString()),409);
        jdbc.sql("UPDATE document_task SET status='SUCCEEDED' WHERE id=:id").param("id",taskId).update();
        jdbc.sql("UPDATE document SET status='READY',active_index_version=1 WHERE id=:id").param("id",documentId).update();
        var viewer=actor(); grant(viewer,"VIEWER");
        expect(reindex(viewer,documentId,UUID.randomUUID().toString()),403);
        var stranger=actor();
        expect(reindex(stranger,documentId,UUID.randomUUID().toString()),404);
        String key=UUID.randomUUID().toString();
        var first=reindex(owner,documentId,key);
        expect(first,202);
        var replay=reindex(owner,documentId,key);
        expect(replay,202);
        assertThat(number(replay,"$.taskId")).isEqualTo(number(first,"$.taskId"));
        expect(reindex(owner,documentId,UUID.randomUUID().toString()),409);
        var state=jdbc.sql("SELECT status,index_version,active_index_version FROM document WHERE id=:id").param("id",documentId).query().singleRow();
        assertThat(state).containsEntry("status","READY").containsEntry("index_version",2).containsEntry("active_index_version",1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task WHERE document_id=:id").param("id",documentId).query(Long.class).single()).isEqualTo(2);
        String payload=jdbc.sql("SELECT payload FROM outbox_event WHERE task_id=:id").param("id",number(first,"$.taskId")).query(String.class).single();
        assertThat(mapper.readTree(payload).path("indexVersion").asInt()).isEqualTo(2);
        expect(reindex(owner,documentId,"short"),400);
    }

    @Test
    void reindexOutboxFailureRollsBackVersionAndNewTask() throws Exception {
        var uploaded=upload(owner,baseId,UUID.randomUUID().toString(),"notes.txt",fixture("txt"));
        long documentId=number(uploaded,"$.documentId");
        jdbc.sql("UPDATE document_task SET status='FAILED' WHERE id=:id").param("id",number(uploaded,"$.taskId")).update();
        jdbc.sql("RENAME TABLE outbox_event TO unavailable_reindex_outbox").update();
        try { expect(reindex(owner,documentId,UUID.randomUUID().toString()),503); }
        finally { jdbc.sql("RENAME TABLE unavailable_reindex_outbox TO outbox_event").update(); }
        assertThat(jdbc.sql("SELECT index_version FROM document WHERE id=:id").param("id",documentId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task WHERE document_id=:id").param("id",documentId).query(Long.class).single()).isEqualTo(1);
    }

    private HttpResponse<String> reindex(Actor actor,long documentId,String key) throws Exception {
        return send(actor,HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/knowledge-bases/"+baseId+"/documents/"+documentId+"/reindex"))
                .header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.noBody()));
    }

    @Test
    void concurrentIdenticalUploadsCreateOneDocumentTaskAndFile() throws Exception {
        concurrentUploads(false);
    }

    private void concurrentUploads(boolean requireBothAccepted) throws Exception {
        String key = UUID.randomUUID().toString();
        long before = fileCount();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return upload(owner, baseId, key, "notes.txt", fixture("txt"));
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return upload(owner, baseId, key, "notes.txt", fixture("txt"));
            });
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.statusCode()).isIn(202, 409);
            assertThat(b.statusCode()).isIn(202, 409);
            assertThat(List.of(a.statusCode(), b.statusCode())).contains(202);
            if (requireBothAccepted) { expect(a, 202); expect(b, 202); }
            var replay = upload(owner, baseId, key, "notes.txt", fixture("txt"));
            expect(replay, 202);
            if (a.statusCode() == 202) assertThat(a.body()).isEqualTo(replay.body());
            if (b.statusCode() == 202) assertThat(b.body()).isEqualTo(replay.body());
        }
        assertThat(documentCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task t JOIN document d ON d.id=t.document_id WHERE d.knowledge_base_id=:base")
                .param("base", baseId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event o JOIN document_task t ON t.id=o.task_id JOIN document d ON d.id=t.document_id WHERE d.knowledge_base_id=:base")
                .param("base", baseId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void heldRequestRejectsUploadAndReindexBeforeCreatingAnything() throws Exception {
        String key = UUID.randomUUID().toString();
        String uploadScope = "upload:" + baseId + ":" + owner.id() + ":" + key;
        redis.opsForValue().set(requestKey(uploadScope), "test-holder", Duration.ofSeconds(30));
        long before = fileCount();
        expect(upload(owner, baseId, key, "notes.txt", fixture("txt")), 409);
        assertThat(fileCount()).isEqualTo(before);
        assertThat(documentCount()).isZero();
        redis.delete(requestKey(uploadScope));
        var first = upload(owner, baseId, key, "notes.txt", fixture("txt"));
        expect(first, 202);
        assertThat(redis.hasKey(requestKey(uploadScope))).isFalse();
        long document = number(first, "$.documentId");
        jdbc.sql("UPDATE document_task SET status='FAILED',cache_version=cache_version+1 WHERE document_id=:id").param("id", document).update();
        String reindexScope = "reindex:" + baseId + ":" + document + ":" + owner.id() + ":" + key;
        redis.opsForValue().set(requestKey(reindexScope), "test-holder", Duration.ofSeconds(30));
        expect(reindex(owner, document, key), 409);
        assertThat(jdbc.sql("SELECT index_version FROM document WHERE id=:id").param("id", document).query(Integer.class).single()).isEqualTo(1);
        redis.delete(requestKey(reindexScope));
        var next = reindex(owner, document, key);
        expect(next, 202);
        assertThat(reindex(owner, document, key).body()).isEqualTo(next.body());
    }

    @Test
    void redisFailureStillUsesDatabaseToDeduplicateConcurrentUploads() throws Exception {
        redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-set");
        try { concurrentUploads(true); }
        finally { redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+set"); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void concurrentReindexUsesDatabaseWhenRedisFails(boolean sameKey) throws Exception {
        var initial = upload(owner, baseId, UUID.randomUUID().toString(), "reindex.txt", fixture("txt"));
        expect(initial, 202);
        long document = number(initial, "$.documentId");
        jdbc.sql("UPDATE document_task SET status='FAILED',cache_version=cache_version+1 WHERE document_id=:id").param("id", document).update();
        String firstKey = UUID.randomUUID().toString();
        String secondKey = sameKey ? firstKey : UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-set");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reindex(owner, document, firstKey); });
            var second = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return reindex(owner, document, secondKey); });
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            if (sameKey) {
                expect(a, 202); expect(b, 202); assertThat(a.body()).isEqualTo(b.body());
            } else {
                assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(202, 409);
            }
        } finally {
            redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+set");
        }
        assertThat(jdbc.sql("SELECT index_version FROM document WHERE id=:id").param("id", document).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task WHERE document_id=:id").param("id", document).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event o JOIN document_task t ON t.id=o.task_id WHERE t.document_id=:id")
                .param("id", document).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void releaseFailureDoesNotChangeSuccessfulResultAndReplayWorksAfterExpiry() throws Exception {
        String key = UUID.randomUUID().toString();
        String scope = "upload:" + baseId + ":" + owner.id() + ":" + key;
        HttpResponse<String> first;
        redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-eval", "-evalsha");
        try {
            first = upload(owner, baseId, key, "notes.txt", fixture("txt"));
            expect(first, 202);
            assertThat(redis.getExpire(requestKey(scope))).isBetween(1L, 30L);
            expect(upload(owner, baseId, key, "notes.txt", fixture("txt")), 409);
        } finally {
            redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+eval", "+evalsha");
        }
        expireRequest(scope);
        var replay = upload(owner, baseId, key, "notes.txt", fixture("txt"));
        expect(replay, 202);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(documentCount()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void requestLockIsHeldUntilOuterTransactionCompletes(boolean rollback) {
        String scope = "transaction:" + UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(idempotency.execute(scope, () -> 42)).isEqualTo(42);
            assertThat(redis.hasKey(requestKey(scope))).isTrue();
            if (rollback) status.setRollbackOnly();
        });
        assertThat(redis.hasKey(requestKey(scope))).isFalse();
    }

    @Test
    void expiredOwnerCannotReleaseNewOwnersLock() throws Exception {
        String scope = "owners:" + UUID.randomUUID();
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var firstExit = new java.util.concurrent.CountDownLatch(1);
        var secondEntered = new java.util.concurrent.CountDownLatch(1);
        var secondExit = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> idempotency.execute(scope, () -> { firstEntered.countDown(); awaitGate(firstExit); return 1; }));
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                expireRequest(scope);
                var second = executor.submit(() -> idempotency.execute(scope, () -> { secondEntered.countDown(); awaitGate(secondExit); return 2; }));
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                String currentToken = redis.opsForValue().get(requestKey(scope));
                firstExit.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(redis.opsForValue().get(requestKey(scope))).isEqualTo(currentToken).isNotNull();
                secondExit.countDown();
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2);
                assertThat(redis.hasKey(requestKey(scope))).isFalse();
            } finally { firstExit.countDown(); secondExit.countDown(); }
        }
    }

    @Test
    void uploadPastLeaseExpiryStillCreatesOnlyOneDatabaseTaskAndFile() throws Exception {
        String key = UUID.randomUUID().toString();
        String scope = "upload:" + baseId + ":" + owner.id() + ":" + key;
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        byte[] bytes = fixture("txt");
        long before = fileCount();
        var slow = new org.springframework.mock.web.MockMultipartFile("file", "notes.txt", "text/plain", bytes) {
            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                entered.countDown(); awaitGate(resume); return super.getInputStream();
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> documents.upload(owner.id(), baseId, key, slow));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                expireRequest(scope);
                var second = documents.upload(owner.id(), baseId, key,
                        new org.springframework.mock.web.MockMultipartFile("file", "notes.txt", "text/plain", bytes));
                resume.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second);
            } finally { resume.countDown(); }
        }
        assertThat(documentCount()).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(before + 1);
    }

    private void expireRequest(String scope) {
        redis.expire(requestKey(scope), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(requestKey(scope))));
    }

    private static void awaitGate(java.util.concurrent.CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }

    private static String requestKey(String scope) { return "knowflow:request:v1:" + scope; }

    @Test
    void permissionsApplyToUploadTaskReadsAndIdempotentRetries() throws Exception {
        var viewer = actor();
        var editor = actor();
        var admin = actor();
        grant(viewer, "VIEWER");
        grant(editor, "EDITOR");
        jdbc.sql("UPDATE app_user SET system_role = 'ADMIN' WHERE id = :id").param("id", admin.id()).update();
        String key = UUID.randomUUID().toString();
        long before = fileCount();
        expect(upload(viewer, baseId, key, "notes.txt", fixture("txt")), 403);
        expect(upload(null, baseId, key, "notes.txt", fixture("txt")), 401);
        var adminUpload = upload(admin, baseId, key, "admin-notes.txt", fixture("txt"));
        expect(adminUpload, 202);
        assertThat(fileCount()).isEqualTo(before + 1);
        var uploaded = upload(editor, baseId, key, "notes.txt", fixture("txt"));
        expect(uploaded, 202);
        String task = "/api/document-tasks/" + number(uploaded, "$.taskId");
        expect(request(viewer, "GET", task, null), 200);
        expect(request(admin, "GET", task, null), 200);
        expect(request(null, "GET", task, null), 401);
        expect(request(owner, "DELETE", "/api/knowledge-bases/" + baseId + "/members/" + editor.id(), null), 204);
        expect(upload(editor, baseId, key, "notes.txt", fixture("txt")), 404);
        expect(request(editor, "GET", task, null), 404);
        jdbc.sql("UPDATE document SET status = 'DELETED' WHERE id = :id").param("id", number(uploaded, "$.documentId")).update();
        expect(request(owner, "GET", task, null), 404);
    }

    @Test
    void idempotencyKeysAreScopedToUserAndKnowledgeBase() throws Exception {
        String key = UUID.randomUUID().toString();
        var editor = actor();
        grant(editor, "EDITOR");
        long anotherBase = createBase(owner);
        var a = upload(owner, baseId, key, "notes.txt", fixture("txt"));
        var b = upload(editor, baseId, key, "notes.txt", fixture("txt"));
        var c = upload(owner, anotherBase, key, "notes.txt", fixture("txt"));
        for (var response : List.of(a, b, c)) {
            expect(response, 202);
        }
        assertThat(List.of(number(a, "$.taskId"), number(b, "$.taskId"), number(c, "$.taskId"))).doesNotHaveDuplicates();
    }

    @Test
    void invalidFilesKeysAndSizeLimitsLeaveNoRowsOrFiles() throws Exception {
        long before = fileCount();
        String key = UUID.randomUUID().toString();
        expect(upload(owner, baseId, key, "empty.txt", new byte[0]), 400);
        expect(upload(owner, baseId, key, "blank.md", " \n\t".getBytes(StandardCharsets.UTF_8)), 400);
        expect(upload(owner, baseId, key, "bad.txt", new byte[]{(byte) 0xff}), 415);
        expect(upload(owner, baseId, key, "fake.pdf", fixture("txt")), 415);
        expect(upload(owner, baseId, key, "fake.docx", fixture("txt")), 415);
        expect(upload(owner, baseId, key, "fake.txt", fixture("pdf")), 415);
        expect(upload(owner, baseId, key, "binary.md", new byte[]{0, 1, 2}), 415);
        expect(upload(owner, baseId, key, "script.exe", fixture("txt")), 415);
        expect(upload(owner, baseId, key, "../escape.txt", fixture("txt")), 400);
        expect(upload(owner, baseId, key, "oversized.txt", new byte[64 * 1024 + 1]), 413);
        expect(upload(owner, baseId, key, "request-too-large.txt", new byte[128 * 1024 + 1]), 413);
        expect(upload(owner, baseId, null, "notes.txt", fixture("txt")), 400);
        expect(upload(owner, baseId, "bad", "notes.txt", fixture("txt")), 400);
        expect(upload(owner, 0, key, "notes.txt", fixture("txt")), 400);
        expect(upload(owner, Long.MAX_VALUE, key, "notes.txt", fixture("txt")), 404);
        expect(send(owner, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge-bases/" + baseId + "/documents"))
                .header("Content-Type", "multipart/form-data").POST(HttpRequest.BodyPublishers.ofString("无效的请求体"))), 400);
        assertThat(fileCount()).isEqualTo(before);
        assertThat(documentCount()).isZero();
    }

    @Test
    void docxExpansionLimitRejectsCompressedBomb() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block = new byte[8192];
            for (int i = 0; i < 33 * 128; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        expect(upload(owner, baseId, UUID.randomUUID().toString(), "bomb.docx", bytes.toByteArray()), 415);
        assertThat(documentCount()).isZero();
    }

    @Test
    void outboxFailureRollsBackDocumentTaskAndRemovesStoredFile() throws Exception {
        long beforeFiles = fileCount();
        long beforeTasks = jdbc.sql("SELECT COUNT(*) FROM document_task").query(Long.class).single();
        jdbc.sql("RENAME TABLE outbox_event TO temporarily_unavailable_outbox").update();
        try {
            expect(upload(owner, baseId, UUID.randomUUID().toString(), "notes.txt", fixture("txt")), 503);
            assertThat(documentCount()).isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task").query(Long.class).single()).isEqualTo(beforeTasks);
            assertThat(fileCount()).isEqualTo(beforeFiles);
        } finally {
            jdbc.sql("RENAME TABLE temporarily_unavailable_outbox TO outbox_event").update();
        }
    }

    @Test
    void storageFailureDoesNotCreateDatabaseRecords() throws Exception {
        // 只替换 JUnit 临时目录，模拟存储不可写，不触碰开发文件。
        Path backup = storageDirectory.resolveSibling(storageDirectory.getFileName() + "-backup");
        Files.move(storageDirectory, backup);
        try {
            Files.write(storageDirectory, new byte[]{1});
            expect(upload(owner, baseId, UUID.randomUUID().toString(), "notes.txt", fixture("txt")), 503);
            assertThat(documentCount()).isZero();
        } finally {
            Files.deleteIfExists(storageDirectory);
            Files.move(backup, storageDirectory);
        }
    }

    private long documentCount() {
        return jdbc.sql("SELECT COUNT(*) FROM document WHERE knowledge_base_id = :id").param("id", baseId).query(Long.class).single();
    }

    private long fileCount() throws Exception {
        try (var paths = Files.list(storageDirectory)) {
            return paths.count();
        }
    }

    private Actor actor() throws Exception {
        var credentials = Map.of("username", "doc_" + UUID.randomUUID().toString().replace("-", ""), "password", "test_password_123");
        var registration = request(null, "POST", "/api/auth/register", credentials);
        expect(registration, 201);
        var login = request(null, "POST", "/api/auth/login", credentials);
        expect(login, 200);
        return new Actor(number(registration, "$.id"), JsonPath.read(login.body(), "$.accessToken"));
    }

    private long createBase(Actor actor) throws Exception {
        var response = request(actor, "POST", "/api/knowledge-bases", Map.of("name", "上传测试库"));
        expect(response, 201);
        return number(response, "$.id");
    }

    private void grant(Actor actor, String role) throws Exception {
        expect(request(owner, "PUT", "/api/knowledge-bases/" + baseId + "/members/" + actor.id(), Map.of("role", role)), 204);
    }

    private HttpResponse<String> upload(Actor actor, long id, String key, String name, byte[] file) throws Exception {
        String boundary = "knowflow-" + UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(file);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge-bases/" + id + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return send(actor, builder);
    }

    private HttpResponse<String> request(Actor actor, String method, String path, Object body) throws Exception {
        return send(actor, HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : mapper.writeValueAsString(body))));
    }

    private HttpResponse<String> send(Actor actor, HttpRequest.Builder builder) throws Exception {
        if (actor != null) {
            builder.header("Authorization", "Bearer " + actor.token());
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long number(HttpResponse<String> response, String path) {
        return ((Number) JsonPath.read(response.body(), path)).longValue();
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("响应体：%s", response.body()).isEqualTo(status);
        if (status >= 400) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        }
    }

    private static byte[] fixture(String extension) throws Exception {
        if (extension.equals("pdf")) {
            var output = new ByteArrayOutputStream();
            output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            var objects = List.of("<< /Type /Catalog /Pages 2 0 R >>",
                    "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>");
            var offsets = new java.util.ArrayList<Integer>();
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(output.size());
                output.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
            }
            int xref = output.size();
            output.write("xref\n0 4\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
            for (int offset : offsets) {
                output.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
            }
            output.write(("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
            return output.toByteArray();
        }
        if (extension.equals("docx")) {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                for (var entry : Map.of(
                        "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                        "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                        "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>测试正文</w:t></w:r></w:p></w:body></w:document>").entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        }
        return "# 知识库\n这是一段测试文档。".getBytes(StandardCharsets.UTF_8);
    }

    private record Actor(long id, String token) {
    }
}
