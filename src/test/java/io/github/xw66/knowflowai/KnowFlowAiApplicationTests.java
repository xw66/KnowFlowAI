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
        properties = "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
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
