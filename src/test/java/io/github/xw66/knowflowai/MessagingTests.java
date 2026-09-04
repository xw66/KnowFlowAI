package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import io.github.xw66.knowflowai.ingestion.OutboxPublisher;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.web-application-type=servlet", "app.outbox.initial-delay=3600000", "app.processing.initial-delay=3600000", "app.embedding.enabled=false",
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.max.block.ms=1000"})
@ActiveProfiles({"api", "worker"})
@Import({KnowFlowAiApplicationTests.DatabaseConfiguration.class, MessagingTests.KafkaConfiguration.class})
class MessagingTests {
    private static final String TOPIC = "knowflow.document.uploaded";
    private static final String DLT = TOPIC + ".DLT";
    private static final String GROUP = "knowflow-document-worker";

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConfiguration {
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.1");
        }
    }

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.document.storage-directory", () -> directory.toString());
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private KafkaContainer broker;
    @Autowired
    private OutboxPublisher publisher;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private io.github.xw66.knowflowai.ingestion.TextTaskProcessor processor;

    @Test
    void textTaskIsChunkedAtomicallyAndReplayDoesNotReprocess() throws Exception {
        var task = upload();
        publisher.publishNext();
        awaitQueued(task.id());
        processUntilClaimed(task.id());
        assertThat(jdbc.sql("SELECT stage FROM document_task WHERE id = :id").param("id", task.id()).query(String.class).single()).isEqualTo("CHUNKED");
        assertThat(jdbc.sql("SELECT content FROM document_chunk c JOIN document_task t ON t.document_id = c.document_id WHERE t.id = :id")
                .param("id", task.id()).query(String.class).single()).isEqualTo("测试知识文档");
        var replay = kafka.send(TOPIC, Long.toString(task.id()), task.payload()).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitCommitted(replay);
        processor.processNext();
        assertThat(jdbc.sql("SELECT attempts FROM document_task WHERE id = :id").param("id", task.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "tampered"})
    void textFailuresAreRecordedAndMissingFileCanRecover(String scenario) throws Exception {
        var task = upload();
        publisher.publishNext();
        awaitQueued(task.id());
        String key = jdbc.sql("SELECT d.storage_key FROM document d JOIN document_task t ON t.document_id = d.id WHERE t.id = :id")
                .param("id", task.id()).query(String.class).single();
        Path file = directory.resolve(key);
        byte[] original = java.nio.file.Files.readAllBytes(file);
        if (scenario.equals("missing")) java.nio.file.Files.delete(file);
        else java.nio.file.Files.writeString(file, "被修改的文件");
        processUntilClaimed(task.id());
        assertThat(jdbc.sql("SELECT status FROM document_task WHERE id = :id").param("id", task.id()).query(String.class).single())
                .isEqualTo(scenario.equals("missing") ? "RETRY_WAIT" : "FAILED");
        java.nio.file.Files.write(file, original);
        if (scenario.equals("missing")) {
            jdbc.sql("UPDATE document_task SET next_attempt_at = CURRENT_TIMESTAMP(6) WHERE id = :id").param("id", task.id()).update();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                processor.processNext();
                assertThat(jdbc.sql("SELECT stage FROM document_task WHERE id = :id").param("id", task.id()).query(String.class).single()).isEqualTo("CHUNKED");
            });
        }
    }

    private void processUntilClaimed(long id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            processor.processNext();
            assertThat(jdbc.sql("SELECT attempts FROM document_task WHERE id = :id").param("id", id).query(Integer.class).single()).isPositive();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx", "encrypted", "broken"})
    void officeUploadFlowsThroughKafkaToChunksOrPermanentFailure(String type) throws Exception {
        byte[] bytes = switch (type) {
            case "docx" -> DocumentParserTests.docx();
            case "broken" -> "%PDF-broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> DocumentParserTests.pdf(type.equals("encrypted"), "First page", "", "Third page");
        };
        var task = upload(type.equals("docx") ? "test.docx" : "test.pdf", bytes);
        publisher.publishNext();
        awaitQueued(task.id());
        processUntilClaimed(task.id());
        var state = jdbc.sql("SELECT status, stage, error_code FROM document_task WHERE id = :id").param("id", task.id()).query().singleRow();
        var chunks = jdbc.sql("SELECT c.content, c.page_number FROM document_chunk c JOIN document_task t ON t.document_id = c.document_id WHERE t.id = :id ORDER BY c.chunk_index")
                .param("id", task.id()).query().listOfRows();
        if (type.equals("encrypted") || type.equals("broken")) {
            assertThat(state).containsEntry("status", "FAILED").containsEntry("error_code", "INVALID_CONTENT");
            assertThat(chunks).isEmpty();
        } else {
            assertThat(state).containsEntry("status", "PENDING").containsEntry("stage", "CHUNKED");
            if (type.equals("pdf")) {
                assertThat(chunks).extracting(row -> row.get("page_number")).containsExactly(1, 3);
            } else {
                assertThat(chunks).extracting(row -> row.get("content")).containsExactly("第一段 😀", "表格左侧", "表格右侧", "末段");
                assertThat(chunks).allMatch(row -> row.get("page_number") == null);
            }
        }
    }

    @Test
    void expiredProcessingLeaseRecoversAndCrashBudgetIsBounded() throws Exception {
        var task = upload();
        publisher.publishNext();
        awaitQueued(task.id());
        jdbc.sql("UPDATE document_task SET status = 'PROCESSING', stage = 'PARSING', attempts = 1, lease_token = 'crashed', lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id = :id")
                .param("id", task.id()).update();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            processor.processNext();
            assertThat(jdbc.sql("SELECT stage FROM document_task WHERE id = :id").param("id", task.id()).query(String.class).single()).isEqualTo("CHUNKED");
        });
        assertThat(jdbc.sql("SELECT attempts FROM document_task WHERE id = :id").param("id", task.id()).query(Integer.class).single()).isEqualTo(2);
        var exhausted = upload();
        publisher.publishNext();
        awaitQueued(exhausted.id());
        jdbc.sql("UPDATE document_task SET status = 'PROCESSING', stage = 'PARSING', attempts = max_attempts, lease_token = 'crashed', lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id = :id")
                .param("id", exhausted.id()).update();
        processor.processNext();
        assertThat(jdbc.sql("SELECT status FROM document_task WHERE id = :id").param("id", exhausted.id()).query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void uploadPublishesAndWorkerReceivesExactlyOnceDespiteReplay() throws Exception {
        var task = upload();
        assertThat(publisher.publishNext()).isTrue();
        awaitQueued(task.id());
        assertThat(eventStatus(task.outboxId())).isEqualTo("PUBLISHED");
        String receipt = receipt(task.id());
        var replay = kafka.send(TOPIC, Long.toString(task.id()), task.payload()).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitCommitted(replay);
        assertThat(receipt(task.id())).isEqualTo(receipt);
        var state = jdbc.sql("SELECT status, stage, attempts FROM document_task WHERE id = :id").param("id", task.id()).query().singleRow();
        assertThat(state).containsEntry("status", "PENDING").containsEntry("stage", "QUEUED").containsEntry("attempts", 0);
        assertThat(publisher.publishNext()).isFalse();
    }

    @Test
    void expiredPublisherLeaseIsRecovered() throws Exception {
        var task = upload();
        jdbc.sql("""
                UPDATE outbox_event SET status = 'PUBLISHING', lease_token = 'abandoned', attempts = 1,
                    lease_until = TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP(6)) WHERE id = :id
                """).param("id", task.outboxId()).update();
        assertThat(publisher.publishNext()).isTrue();
        awaitQueued(task.id());
        var row = jdbc.sql("SELECT status, attempts, lease_token, published_at FROM outbox_event WHERE id = :id")
                .param("id", task.outboxId()).query().singleRow();
        assertThat(row).containsEntry("status", "PUBLISHED").containsEntry("attempts", 2).containsEntry("lease_token", null);
        assertThat(row.get("published_at")).isNotNull();
    }

    @Test
    void concurrentPublishersDoNotClaimSameUnexpiredEvent() throws Exception {
        var task = upload();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return publisher.publishNext(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return publisher.publishNext(); });
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        awaitQueued(task.id());
        assertThat(jdbc.sql("SELECT attempts FROM outbox_event WHERE id = :id").param("id", task.outboxId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void brokerFailureKeepsEventPendingAndRetriesAfterRecovery() throws Exception {
        var task = upload();
        // 暂停的只是 Testcontainers 测试 broker，不影响 Compose 开发环境。
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        try {
            assertThat(publisher.publishNext()).isFalse();
            assertThat(eventStatus(task.outboxId())).isEqualTo("PENDING");
            assertThat(jdbc.sql("SELECT published_at IS NULL AND available_at > CURRENT_TIMESTAMP(6) FROM outbox_event WHERE id = :id")
                    .param("id", task.outboxId()).query(Boolean.class).single()).isTrue();
        } finally {
            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            jdbc.sql("UPDATE outbox_event SET available_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id = :id")
                    .param("id", task.outboxId()).update();
            publisher.publishNext();
            assertThat(eventStatus(task.outboxId())).isEqualTo("PUBLISHED");
        });
        awaitQueued(task.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "wrongVersion", "tombstone"})
    void invalidEventsReachDeadLetterAndDoNotBlockFollowingValidTask(String scenario) throws Exception {
        var task = upload();
        String payload = switch (scenario) {
            case "wrongVersion" -> mapper.writeValueAsString(Map.of("taskId", task.id(), "indexVersion", 99));
            case "tombstone" -> null;
            default -> "invalid-json-" + UUID.randomUUID();
        };
        try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", broker.getBootstrapServers(),
                "group.id", "dlt-test-" + UUID.randomUUID(), "enable.auto.commit", false), new StringDeserializer(), new StringDeserializer())) {
            var partitions = List.of(new TopicPartition(DLT, 0), new TopicPartition(DLT, 1), new TopicPartition(DLT, 2));
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            var metadata = kafka.send(TOPIC, Long.toString(task.id()), payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (Long.toString(task.id()).equals(record.key()) && java.util.Objects.equals(payload, record.value())) {
                        return true;
                    }
                }
                return false;
            });
            awaitCommitted(metadata);
            assertThat(receipt(task.id())).isNull();
            assertThat(publisher.publishNext()).isTrue();
            awaitQueued(task.id());
        }
    }

    @Test
    void databaseFailureDoesNotCommitOffsetAndRecoversWithoutDroppingMessage() throws Exception {
        var task = upload();
        jdbc.sql("RENAME TABLE document_task TO temporarily_unavailable_tasks").update();
        RecordMetadata metadata;
        try {
            metadata = kafka.send(TOPIC, Long.toString(task.id()), task.payload()).get(10, TimeUnit.SECONDS).getRecordMetadata();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                        assertThat(committed(admin, metadata)).isLessThan(metadata.offset() + 1));
            }
        } finally {
            jdbc.sql("RENAME TABLE temporarily_unavailable_tasks TO document_task").update();
        }
        awaitQueued(task.id());
        awaitCommitted(metadata);
        assertThat(publisher.publishNext()).isTrue();
        assertThat(eventStatus(task.outboxId())).isEqualTo("PUBLISHED");
    }

    private String eventStatus(long id) {
        return jdbc.sql("SELECT status FROM outbox_event WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String receipt(long id) {
        return (String) jdbc.sql("SELECT CAST(received_at AS CHAR) AS receipt FROM document_task WHERE id = :id")
                .param("id", id).query().singleRow().get("receipt");
    }

    private void awaitQueued(long id) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(receipt(id)).isNotNull());
    }

    private void awaitCommitted(RecordMetadata metadata) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(committed(admin, metadata)).isGreaterThanOrEqualTo(metadata.offset() + 1));
        }
    }

    private static long committed(Admin admin, RecordMetadata metadata) throws Exception {
        var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(metadata.topic(), metadata.partition()));
        return offset == null ? -1 : offset.offset();
    }

    private Task upload() throws Exception {
        return upload("test.txt", "测试知识文档".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Task upload(String filename, byte[] fileBytes) throws Exception {
        String credentials = mapper.writeValueAsString(Map.of("username", "kafka_" + UUID.randomUUID().toString().replace("-", ""), "password", "test_password_123"));
        assertThat(http("POST", "/api/auth/register", credentials, null, "application/json", null).statusCode()).isEqualTo(201);
        var login = http("POST", "/api/auth/login", credentials, null, "application/json", null);
        assertThat(login.statusCode()).isEqualTo(200);
        String token = JsonPath.read(login.body(), "$.accessToken");
        var base = http("POST", "/api/knowledge-bases", "{\"name\":\"Kafka 测试库\"}", token, "application/json", null);
        assertThat(base.statusCode()).isEqualTo(201);
        long baseId = ((Number) JsonPath.read(base.body(), "$.id")).longValue();
        String boundary = "test-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(fileBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var upload = httpBytes("POST", "/api/knowledge-bases/" + baseId + "/documents", body.toByteArray(), token,
                "multipart/form-data; boundary=" + boundary, UUID.randomUUID().toString());
        assertThat(upload.statusCode()).isEqualTo(202);
        long id = ((Number) JsonPath.read(upload.body(), "$.taskId")).longValue();
        return jdbc.sql("SELECT task_id AS id, id AS outbox_id, payload FROM outbox_event WHERE task_id = :id")
                .param("id", id).query(Task.class).single();
    }

    private HttpResponse<String> http(String method, String path, String body, String token, String contentType, String key) throws Exception {
        return httpBytes(method, path, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), token, contentType, key);
    }

    private HttpResponse<String> httpBytes(String method, String path, byte[] body, String token, String contentType, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private record Task(long id, long outboxId, String payload) {
    }
}
