package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.xw66.knowflowai.web.RateLimits;
import io.github.xw66.knowflowai.web.RateLimits.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=true", "app.rate-limit.window=PT60S",
        "app.rate-limit.register=2", "app.rate-limit.login=2", "app.rate-limit.search=2", "app.rate-limit.answer=2",
        "app.cache.enabled=false", "app.chat.enabled=false", "app.embedding.enabled=false", "app.bm25.enabled=false", "app.rerank.enabled=false"})
@Import({KnowFlowAiApplicationTests.DatabaseConfiguration.class, KnowledgeBaseTests.RedisConfiguration.class})
@ActiveProfiles("test")
class RateLimitTests {
    @LocalServerPort int port;
    @Autowired RateLimits limits;
    @Autowired StringRedisTemplate redis;
    @Autowired GenericContainer<?> redisContainer;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    private int forwardedId;

    @BeforeEach
    void clearIsolatedCounters() {
        // 只删除本测试类隔离 Redis 中的限流键。
        var keys = redis.keys("knowflow:rate:v1:*");
        if (!keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void registrationAndLoginHaveIndependentIpQuotasAndIgnoreSpoofedForwarding() throws Exception {
        for (String path : List.of("/api/auth/register", "/api/auth/login")) {
            assertThat(post(path, null).statusCode()).isEqualTo(400);
            assertThat(post(path, null).statusCode()).isEqualTo(400);
            assertLimited(post(path, null));
        }
    }

    @Test
    void protectedQuotasFollowUserAcrossKnowledgeBasesAndShareSyncAndStream() throws Exception {
        String first = token(), second = token();
        assertThat(post("/api/knowledge-bases/1/search", null).statusCode()).isEqualTo(401);
        assertThat(post("/api/knowledge-bases/1/search", "invalid").statusCode()).isEqualTo(401);
        assertThat(post("/api/knowledge-bases/1/search", first).statusCode()).isEqualTo(400);
        assertThat(post("/api/knowledge-bases/2/search", first).statusCode()).isEqualTo(400);
        assertLimited(post("/api/knowledge-bases/3/search", first));
        assertThat(post("/api/knowledge-bases/1/search", second).statusCode()).isEqualTo(400);
        assertThat(post("/api/knowledge-bases/1/answers", first).statusCode()).isEqualTo(400);
        assertThat(post("/api/knowledge-bases/2/answers/stream", first).statusCode()).isEqualTo(400);
        assertLimited(post("/api/knowledge-bases/1/answers/stream", first));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM chat_message").query(Long.class).single()).isZero();
    }

    @Test
    void concurrentCallersCannotExceedQuota() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 40; i++) results.add(executor.submit(() -> {
                gate.await(); return limits.acquire(Bucket.SEARCH, "concurrent");
            }));
            gate.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS) == 0) allowed++;
            assertThat(allowed).isEqualTo(2);
        }
        assertThat(redis.opsForValue().get(key(Bucket.SEARCH, "concurrent"))).isEqualTo("2");
    }

    @Test
    void deniedRequestsDoNotExtendWindowAndExpirationRestoresQuota() {
        String key = key(Bucket.LOGIN, "expiry");
        assertThat(limits.acquire(Bucket.LOGIN, "expiry")).isZero();
        assertThat(limits.acquire(Bucket.LOGIN, "expiry")).isZero();
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(limits.acquire(Bucket.LOGIN, "expiry")).isBetween(1L, before);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        redis.expire(key, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThat(limits.acquire(Bucket.LOGIN, "expiry")).isZero();
    }

    @Test
    void missingTtlIsRepairedAndCorruptCounterFailsClosed() {
        redis.opsForValue().set(key(Bucket.SEARCH, "repair"), "2");
        assertThat(limits.acquire(Bucket.SEARCH, "repair")).isPositive();
        assertThat(redis.getExpire(key(Bucket.SEARCH, "repair"))).isBetween(1L, 60L);
        redis.opsForValue().set(key(Bucket.SEARCH, "bad"), "broken");
        assertThatThrownBy(() -> limits.acquire(Bucket.SEARCH, "bad"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void redisFailureRejectsEveryLimitedEndpointWithoutStartingConversation() throws Exception {
        String token = token();
        redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-eval", "-evalsha");
        try {
            for (String path : List.of("/api/auth/register", "/api/auth/login", "/api/knowledge-bases/1/search",
                    "/api/knowledge-bases/1/answers", "/api/knowledge-bases/1/answers/stream")) {
                var response = post(path, token);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
                assertThat(response.body()).doesNotContain("NOPERM", "EVAL", "redis://");
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM chat_message").query(Long.class).single()).isZero();
        } finally {
            redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+eval", "+evalsha");
        }
        assertThat(post("/api/auth/login", null).statusCode()).isEqualTo(400);
    }

    @Test
    void redisTimeoutFailsClosedAndInvalidLimitsFailAtStartup() throws Exception {
        assertThatThrownBy(() -> new RateLimits(redis, Duration.ZERO, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimits(redis, Duration.ofSeconds(60), 0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        redisContainer.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL");
        try {
            long started = System.nanoTime();
            assertThat(post("/api/auth/login", null).statusCode()).isEqualTo(503);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        } finally {
            redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    private static String key(Bucket bucket, String subject) {
        return "knowflow:rate:v1:" + bucket.name() + ":" + subject;
    }

    private static void assertLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("X-Forwarded-For", "192.0.2." + (++forwardedId))
                .header("Forwarded", "for=198.51.100.1").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }

    private String token() {
        String name = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO app_user(username,password_hash) VALUES (:name,'unused')").param("name", name).update();
        long user = jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name", name).query(Long.class).single();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("knowflow-ai").audience(List.of("knowflow-api"))
                .subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
