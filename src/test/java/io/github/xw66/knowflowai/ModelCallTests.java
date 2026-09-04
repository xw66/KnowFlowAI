package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.github.xw66.knowflowai.observability.ModelCallLog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.cache.enabled=false","app.rate-limit.enabled=false","app.idempotency.enabled=false","management.health.redis.enabled=false",
        "app.chat.enabled=false","app.embedding.enabled=false","app.bm25.enabled=false","app.rerank.enabled=false"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
@ActiveProfiles("test")
class ModelCallTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ModelCallLog log;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtEncoder encoder;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void clear() { jdbc.sql("DELETE FROM model_call").update(); }
    long start() { return log.start(UUID.randomUUID().toString(),1,"PRIMARY",false,null,"test-model"); }

    @Test void ledgerSurvivesOuterRollbackAndTerminalCannotBeOverwritten() {
        new TransactionTemplate(manager).executeWithoutResult(status-> {
            long id=start();
            log.finish(id,"COMPLETED","actual",new ModelCallLog.Tokens(5,2,7),25,null);
            log.finish(id,"FAILED",null,null,99,"LateError");
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT total_tokens FROM model_call").query(Integer.class).single()).isEqualTo(7);
    }
    @Test void failedFinalWriteRemainsUnresolvedAndCanBeReconciled() {
        long id=start();
        jdbc.sql("RENAME TABLE model_call TO model_call_unavailable").update();
        try { assertThatCode(()->log.finish(id,"COMPLETED","actual",new ModelCallLog.Tokens(5,2,7),25,null)).doesNotThrowAnyException(); }
        finally { jdbc.sql("RENAME TABLE model_call_unavailable TO model_call").update(); }
        assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE total_tokens IS NULL AND NOT usage_known").query(Long.class).single()).isEqualTo(1);
        log.finish(id,"COMPLETED","actual",new ModelCallLog.Tokens(5,2,7),25,null);
        assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("COMPLETED");
    }
    @Test void administratorOnlyAndRoleRevocationIsImmediate() throws Exception {
        String user=token("USER"),admin=token("ADMIN");
        for(String path:List.of("","/summary")) {
            assertThat(get(path,null).statusCode()).isEqualTo(401);
            assertThat(get(path,user).statusCode()).isEqualTo(403);
            var response=get(path,admin);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        jdbc.sql("UPDATE app_user SET system_role='USER' WHERE system_role='ADMIN'").update();
        assertThat(get("",admin).statusCode()).isEqualTo(403);
    }
    @Test void summaryCountsAttemptsAndOnlyKnownTokensWithValidatedPagination() throws Exception {
        String admin=token("ADMIN");
        long first=start();
        log.finish(first,"COMPLETED","actual",new ModelCallLog.Tokens(5,2,7),20,null);
        log.finish(start(),"FAILED",null,null,40,"TimeoutException");
        start();
        var summary=json.readTree(get("/summary",admin).body());
        assertThat(summary.path("attempts").asInt()).isEqualTo(3);
        assertThat(summary.path("knownUsageCalls").asInt()).isEqualTo(1);
        assertThat(summary.path("unknownUsageCalls").asInt()).isEqualTo(2);
        assertThat(summary.path("knownTotalTokens").asInt()).isEqualTo(7);
        assertThat(summary.path("averageLatencyMs").asDouble()).isEqualTo(30);
        var page=json.readTree(get("?afterId="+first+"&limit=1",admin).body());
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.get(0).path("status").asText()).isEqualTo("FAILED");
        for(String invalid:List.of("?limit=0","?limit=101","?afterId=-1","/summary?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z","/summary?from=bad"))
            assertThat(get(invalid,admin).statusCode()).as(invalid).isEqualTo(400);
        jdbc.sql("DELETE FROM model_call").update();
        assertThat(json.readTree(get("/summary",admin).body()).path("knownTotalTokens").isNull()).isTrue();
    }
    HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/admin/model-calls"+path)).timeout(java.time.Duration.ofSeconds(5));
        if(token!=null) request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    String token(String role) {
        String name=UUID.randomUUID().toString().replace("-","");
        jdbc.sql("INSERT INTO app_user(username,password_hash,system_role) VALUES (:name,'unused',:role)").param("name",name).param("role",role).update();
        long id=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();
        var now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("knowflow-ai").audience(List.of("knowflow-api")).subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
