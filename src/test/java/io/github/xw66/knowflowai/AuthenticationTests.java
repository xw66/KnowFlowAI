package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.cache.enabled=false", "app.rate-limit.enabled=false", "app.idempotency.enabled=false", "management.health.redis.enabled=false"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
@ActiveProfiles("test")
class AuthenticationTests {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JwtEncoder encoder;
    @Autowired
    private JwtDecoder decoder;

    @Test
    void loginIssuesValidatedTokenAndReturnsCurrentUserWithoutCredentials() throws Exception {
        var username = register();
        var response = login(username.toUpperCase(Locale.ROOT), "test_password_123");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsOnlyKeys("accessToken", "tokenType", "expiresIn")
                .containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 900);
        var token = (String) body.get("accessToken");
        var jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(id(username)));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("knowflow-ai");
        assertThat(jwt.getAudience()).containsExactly("knowflow-api");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "password_hash", "roles");
        var me = send("GET", "/api/auth/me", "", token);
        assertThat(me.statusCode()).isEqualTo(200);
        Map<String, Object> account = JsonPath.read(me.body(), "$");
        assertThat(account).containsOnlyKeys("id", "username", "role", "status")
                .containsEntry("username", username).containsEntry("role", "USER").containsEntry("status", "ACTIVE");
        assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledUserHaveSamePublicError() throws Exception {
        var username = register();
        var wrong = login(username, "wrong_password_123");
        var absent = login("absent_" + UUID.randomUUID().toString().replace("-", ""), "test_password_123");
        jdbcClient.sql("UPDATE app_user SET status = 'DISABLED' WHERE username = :username").param("username", username).update();
        var disabled = login(username, "test_password_123");
        for (var response : List.of(wrong, absent, disabled)) {
            assertProblem(response, 401);
            assertThat(response.body()).isEqualTo(wrong.body()).doesNotContain("accessToken", "test_password_123", username);
        }
    }

    @Test
    void databaseRolesAndAccountStatusTakeEffectOnExistingToken() throws Exception {
        var username = register();
        var token = token(username);
        var path = "/api/admin/users/" + id(username);
        assertProblem(send("GET", path, "", token), 403);
        jdbcClient.sql("UPDATE app_user SET system_role = 'ADMIN' WHERE username = :username").param("username", username).update();
        var admin = send("GET", path, "", token);
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).doesNotContain("password", "bcrypt");
        assertProblem(send("GET", "/api/admin/users/0", "", token), 400);
        assertProblem(send("GET", "/api/admin/users/9223372036854775807", "", token), 404);
        assertProblem(send("GET", "/actuator/env", "", token), 403);
        jdbcClient.sql("UPDATE app_user SET system_role = 'USER' WHERE username = :username").param("username", username).update();
        assertProblem(send("GET", path, "", token), 403);
        jdbcClient.sql("UPDATE app_user SET status = 'DISABLED' WHERE username = :username").param("username", username).update();
        assertProblem(send("GET", "/api/auth/me", "", token), 401);
        jdbcClient.sql("DELETE FROM app_user WHERE username = :username").param("username", username).update();
        assertProblem(send("GET", "/api/auth/me", "", token), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrongIssuer", "wrongAudience", "noAudience", "noExpiry", "noSubject",
            "invalidSubject", "futureNotBefore", "forgedSignature", "unsigned", "malformed"})
    void rejectsInvalidTokens(String scenario) throws Exception {
        var username = register();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuedAt(now.minusSeconds(120))
                .issuer(scenario.equals("wrongIssuer") ? "other-issuer" : "knowflow-ai");
        if (!scenario.equals("noAudience")) {
            claims.audience(List.of(scenario.equals("wrongAudience") ? "other-api" : "knowflow-api"));
        }
        if (!scenario.equals("noSubject")) {
            claims.subject(scenario.equals("invalidSubject") ? "not-a-user-id" : Long.toString(id(username)));
        }
        if (!scenario.equals("noExpiry")) {
            claims.expiresAt(scenario.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(600));
        }
        if (scenario.equals("futureNotBefore")) {
            claims.notBefore(now.plusSeconds(600));
        }
        JwtEncoder signer = scenario.equals("forgedSignature")
                ? NimbusJwtEncoder.withSecretKey(new SecretKeySpec(new byte[32], "HmacSHA256")).algorithm(MacAlgorithm.HS256).build()
                : encoder;
        var encoded = signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build()))
                .getTokenValue();
        if (scenario.equals("unsigned")) {
            encoded = "eyJhbGciOiJub25lIn0." + encoded.split("\\.")[1] + ".";
        } else if (scenario.equals("malformed")) {
            encoded = "malformed-token";
        }
        var response = send("GET", "/api/auth/me", "", encoded);
        assertProblem(response, 401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).isEqualTo("Bearer");
        assertThat(response.body()).doesNotContain(encoded, "stackTrace");
    }

    @Test
    void signedRoleClaimCannotGrantAdminAccess() throws Exception {
        var username = register();
        var claims = JwtClaimsSet.builder().issuer("knowflow-ai").audience(List.of("knowflow-api"))
                .subject(Long.toString(id(username))).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("ADMIN")).claim("scope", "ROLE_ADMIN").build();
        var forgedRoleToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertProblem(send("GET", "/api/admin/users/" + id(username), "", forgedRoleToken), 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/admin/users/1", "/api/unknown", "/api/auth/login", "/api/auth/register"})
    void anonymousRequestsCannotUseProtectedEndpoints(String path) throws Exception {
        assertProblem(send("GET", path, "", null), 401);
    }

    @Test
    void queryParameterDoesNotAuthenticate() throws Exception {
        var username = register();
        assertProblem(send("GET", "/api/auth/me?access_token=" + token(username), "", null), 401);
    }

    @Test
    void invalidLoginInputIsRejectedWithoutExposingPassword() throws Exception {
        for (var password : List.of("short", "1234567", "密".repeat(25), "a".repeat(73))) {
            var response = login("valid_username", password);
            assertProblem(response, 400);
            assertThat(response.body()).doesNotContain(password);
        }
        assertProblem(send("POST", "/api/auth/login", "{}", null), 400);
        assertProblem(send("POST", "/api/auth/login", "{\"username\":\"valid_name\",\"password\":\"test_password_123\",\"role\":\"ADMIN\"}", null), 400);
    }

    @Test
    void eightCharacterPasswordRegistersAndLogsInButSevenIsRejected() throws Exception {
        String username = "length_" + UUID.randomUUID().toString().replace("-", "");
        assertProblem(send("POST", "/api/auth/register", credentials(username, "Ab12345"), null), 400);
        assertThat(send("POST", "/api/auth/register", credentials(username, "Ab123456"), null).statusCode()).isEqualTo(201);
        assertThat(login(username, "Ab123456").statusCode()).isEqualTo(200);
    }

    @Test
    void databaseFailureCannotAuthenticateAndDoesNotLeakSql() throws Exception {
        var username = register();
        var token = token(username);
        // 只在临时测试库内制造查询故障，验证鉴权不会在数据库失败时放行。
        jdbcClient.sql("RENAME TABLE app_user TO temporarily_unavailable_users").update();
        try {
            for (var response : List.of(send("GET", "/api/auth/me", "", token), login(username, "test_password_123"))) {
                assertProblem(response, 503);
                assertThat(response.body()).doesNotContain("SELECT", "app_user", "password", token);
            }
        } finally {
            jdbcClient.sql("RENAME TABLE temporarily_unavailable_users TO app_user").update();
        }
    }

    private String register() throws Exception {
        var username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(send("POST", "/api/auth/register", credentials(username, "test_password_123"), null).statusCode()).isEqualTo(201);
        return username;
    }

    private long id(String username) {
        return jdbcClient.sql("SELECT id FROM app_user WHERE username = :username").param("username", username).query(Long.class).single();
    }

    private String token(String username) throws Exception {
        var response = login(username, "test_password_123");
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.accessToken");
    }

    private String credentials(String username, String password) {
        return mapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return send("POST", "/api/auth/login", credentials(username, password), null);
    }

    private static void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat((int) JsonPath.read(response.body(), "$.status")).isEqualTo(status);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
