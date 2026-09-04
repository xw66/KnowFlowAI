package io.github.xw66.knowflowai;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.cache.enabled=false", "app.rate-limit.enabled=false", "app.idempotency.enabled=false", "management.health.redis.enabled=false"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
@ActiveProfiles("test")
class KnowFlowAiApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        MySQLContainer mysql() {
            return new MySQLContainer("mysql:8.4.8");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void openApiMatchesAuthenticationAndMultipartContracts() throws Exception {
        var response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        var api = objectMapper.readTree(response.body());
        assertThat(api.path("openapi").asText()).startsWith("3.0.");
        assertThat(api.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText()).isEqualTo("bearer");
        assertThat(api.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(api.path("paths").path("/api/auth/register").path("post").path("security")).isEmpty();
        assertThat(api.path("paths").path("/api/auth/login").path("post").path("security")).isEmpty();
        var upload = api.path("paths").path("/api/knowledge-bases/{id}/documents").path("post");
        assertThat(upload.path("requestBody").path("content").has("multipart/form-data")).isTrue();
        assertThat(upload.path("parameters")).anyMatch(parameter -> parameter.path("name").asText().equals("Idempotency-Key"));
        var search = api.path("paths").path("/api/knowledge-bases/{id}/search").path("post");
        assertThat(search.path("parameters")).noneMatch(parameter -> parameter.path("name").asText().equals("account"));
        assertThat(search.at("/responses/200/headers").has("X-Rerank-Status")).isTrue();
        assertThat(search.at("/responses/200/content")).anyMatch(content -> content.at("/schema/type").asText().equals("array"));
        assertThat(api.path("paths").has("/actuator/health")).isFalse();
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
        assertThat(get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
        assertThat(get("/api/knowledge-bases").statusCode()).isEqualTo(401);
    }

    @Test
    void openApiPreservesValidatedParametersBinaryUploadAndResponseTypes() throws Exception {
        var api=objectMapper.readTree(get("/v3/api-docs").body());
        var parameters=api.path("paths").path("/api/knowledge-bases/{id}/documents").path("get").path("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("id");
            assertThat(parameter.path("required").asBoolean()).isTrue();
            assertThat(parameter.at("/schema/type").asText()).isEqualTo("integer");
            assertThat(parameter.at("/schema/minimum").asInt(-1)).isZero();
            assertThat(parameter.at("/schema/exclusiveMinimum").asBoolean()).isTrue();
        }).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("limit");
            assertThat(parameter.at("/schema/default").asInt()).isEqualTo(50);
            assertThat(parameter.at("/schema/minimum").asInt()).isEqualTo(1);
            assertThat(parameter.at("/schema/maximum").asInt()).isEqualTo(100);
        });
        var upload=api.path("paths").path("/api/knowledge-bases/{id}/documents").path("post");
        var body=upload.path("requestBody").path("content").path("multipart/form-data").path("schema");
        assertThat(body.path("required")).anyMatch(field -> field.asText().equals("file"));
        assertThat(body.at("/properties/file/type").asText()).isEqualTo("string");
        assertThat(body.at("/properties/file/format").asText()).isEqualTo("binary");
        var schemas=api.at("/components/schemas");
        assertThat(schemas.at("/SearchRequest/properties/query/maxLength").asInt()).isEqualTo(2000);
        assertThat(schemas.at("/SearchRequest/properties/topK/maximum").asInt()).isEqualTo(20);
        assertThat(schemas.at("/AnswerRequest/properties/topK/maximum").asInt()).isEqualTo(8);
        assertThat(schemas.at("/AnswerRequest/properties/question/maxLength").asInt()).isEqualTo(2000);
        assertThat(api.path("paths").has("/api/knowledge-bases/{id}/answers")).isTrue();
        assertThat(api.path("paths").has("/api/conversations")).isTrue();
        assertThat(api.path("paths").has("/api/conversations/{id}/messages")).isTrue();
        assertThat(schemas.at("/AnswerRequest/properties/conversationId/format").asText()).isEqualTo("int64");
        assertThat(schemas.at("/AnswerRequest/properties/rewrite/type").asText()).isEqualTo("boolean");
        assertThat(api.path("paths").path("/api/knowledge-bases/{id}/answers/stream")
                .at("/post/responses/200/content").has("text/event-stream")).isTrue();
        assertThat(schemas.at("/SearchRequest/properties/mode/enum")).extracting(node -> node.asText()).containsExactly("VECTOR","BM25","HYBRID");
        assertThat(schemas.at("/DocumentView/properties/id/format").asText()).isEqualTo("int64");
        assertThat(schemas.at("/DocumentView/properties/createdAt/format").asText()).isEqualTo("date-time");
    }

    @Test
    void healthReturnsUpWithoutInternalDetails() throws Exception {
        var response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsEntry("status", "UP")
                .containsOnlyKeys("status", "groups");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans"})
    void sensitiveEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(401);
    }

    @Test
    void flywayMigratesRealMySql() {
        assertThat(jdbcClient.sql("SELECT VERSION()").query(String.class).single()).startsWith("8.4.");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void registrationStoresSaltedHashAndReturnsOnlyPublicFields() throws Exception {
        var username = "User_" + UUID.randomUUID().toString().replace("-", "");
        var password = "test_password_123";
        var response = post(username, password);
        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("id", "username", "role")
                .containsEntry("username", username.toLowerCase(java.util.Locale.ROOT))
                .containsEntry("role", "USER");
        var row = jdbcClient.sql("SELECT * FROM app_user WHERE id = :id").param("id", body.get("id"))
                .query().singleRow();
        var hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}").isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, hash)).isTrue();
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("system_role", "USER");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(post(username + "_2", password).statusCode()).isEqualTo(201);
        var secondHash = jdbcClient.sql("SELECT password_hash FROM app_user WHERE username = :username")
                .param("username", username.toLowerCase(java.util.Locale.ROOT) + "_2").query(String.class).single();
        assertThat(secondHash).isNotEqualTo(hash);
        assertThat(passwordEncoder.matches(password, secondHash)).isTrue();
    }

    @Test
    void concurrentCaseVariantsCreateExactlyOneUser() throws Exception {
        var username = "race_" + UUID.randomUUID().toString().replace("-", "");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return post(username, "test_password_123");
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return post(username.toUpperCase(java.util.Locale.ROOT), "test_password_123");
            });
            var responses = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            var conflict = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertThat(conflict.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
            assertThat(conflict.body()).contains("用户名已存在").doesNotContain("INSERT", "password_hash", "test_password_123");
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app_user WHERE username = :username")
                .param("username", username).query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"username\":null,\"password\":null}",
            "{\"username\":\"ab\",\"password\":\"test_password_123\"}",
            "{\"username\":\"bad-name\",\"password\":\"test_password_123\"}",
            "{\"username\":\" bad_name \",\"password\":\"test_password_123\"}",
            "{\"username\":\"valid_name\",\"password\":\"shortsecret\"}",
            "{\"username\":\"valid_name\",\"password\":\"            \"}",
            "{\"username\":\"valid_name\",\"password\":\"test_password_123\",\"role\":\"ADMIN\"}",
            "{\"username\":\"valid_name\",\"password\":\"test_password_123\",\"systemRole\":\"ADMIN\"}",
            "{\"username\":\"valid_name\",\"password\":\"SECRET_UNFINISHED"
    })
    void invalidRegistrationDoesNotWriteOrExposeSecrets(String json) throws Exception {
        var before = jdbcClient.sql("SELECT COUNT(*) FROM app_user").query(Integer.class).single();
        var response = post(json);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(response.body()).doesNotContain("test_password_123", "shortsecret", "SECRET_UNFINISHED", "stackTrace");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app_user").query(Integer.class).single()).isEqualTo(before);
    }

    @Test
    void passwordByteBoundaryAndUsernameLengthAreValidated() throws Exception {
        var username = "bytes_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(post(username, "密".repeat(25)).statusCode()).isEqualTo(400);
        assertThat(post(username, "a".repeat(73)).statusCode()).isEqualTo(400);
        assertThat(post("a".repeat(65), "test_password_123").statusCode()).isEqualTo(400);
        assertThat(post(username, "密".repeat(24)).statusCode()).isEqualTo(201);
        var hash = jdbcClient.sql("SELECT password_hash FROM app_user WHERE username = :username")
                .param("username", username).query(String.class).single();
        assertThat(passwordEncoder.matches("密".repeat(24), hash)).isTrue();
    }

    private HttpResponse<String> post(String username, String password) throws Exception {
        return post(objectMapper.writeValueAsString(Map.of("username", username, "password", password)));
    }

    private HttpResponse<String> post(String json) throws Exception {
        return send("POST", "/api/auth/register", json);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, "");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

}
