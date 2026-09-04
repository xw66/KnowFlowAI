package io.github.xw66.knowflowai;

import java.net.URI;
import java.math.BigDecimal;
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
    @BeforeEach void clear() { jdbc.sql("DELETE FROM model_call").update(); jdbc.sql("UPDATE model_budget SET limit_cny=20,spent_cny=0,held_cny=0,halted=FALSE WHERE id=1").update(); }
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
    @Test void totalOnlyUsageDoesNotBecomeInputOrOutputTokens() throws Exception {
        long id=log.start(UUID.randomUUID().toString(),1,"RERANK","PRIMARY",false,null,"test-rerank");
        log.finish(id,"COMPLETED",null,new ModelCallLog.Tokens(null,null,11),10,null);
        var summary=json.readTree(get("/summary",token("ADMIN")).body());
        assertThat(summary.path("knownUsageCalls").asInt()).isEqualTo(1);
        assertThat(summary.path("knownTotalTokens").asInt()).isEqualTo(11);
        assertThat(summary.path("knownInputTokens").isNull()).isTrue();
        assertThat(summary.path("knownOutputTokens").isNull()).isTrue();
    }

    private static final String BEIJING="https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String RERANK="https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    long priced(String type,String model,String endpoint) {
        return log.start(UUID.randomUUID().toString(),1,type,"PRIMARY",false,null,model,null,endpoint);
    }
    java.math.BigDecimal cost(long id) {
        return jdbc.sql("SELECT estimated_cost FROM model_call WHERE id=:id").param("id",id).query((row,index)->row.getBigDecimal(1)).list().getFirst();
    }
    @Test void computesEachBillingBasisAndExposesKnownAndUnknownCosts() throws Exception {
        long chat=priced("CHAT","qwen3.8-flash",BEIJING+"/");
        log.finish(chat,"COMPLETED","qwen3.8-flash",new ModelCallLog.Tokens(1000,500,1500),1,null);
        assertThat(cost(chat)).isEqualByComparingTo("0.00215");
        long rewrite=priced("REWRITE","qwen3.8-flash",BEIJING);
        log.finish(rewrite,"FAILED",null,new ModelCallLog.Tokens(1000,0,1000),1,"InvalidResponse");
        assertThat(cost(rewrite)).isEqualByComparingTo("0.0008");
        long embedding=priced("EMBEDDING","text-embedding-v4",BEIJING);
        log.finish(embedding,"COMPLETED",null,new ModelCallLog.Tokens(1000,null,1000),1,null);
        assertThat(cost(embedding)).isEqualByComparingTo("0.0005");
        long rank=priced("RERANK","gte-rerank-v2",RERANK);
        log.finish(rank,"COMPLETED",null,new ModelCallLog.Tokens(null,null,1000),1,null);
        assertThat(cost(rank)).isEqualByComparingTo("0.0008");
        start();
        String admin=token("ADMIN");
        var summary=json.readTree(get("/summary",admin).body());
        assertThat(summary.path("estimatedCalls").asInt()).isEqualTo(4);
        assertThat(summary.path("unknownCostCalls").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(summary.path("estimatedCostCny").asText())).isEqualByComparingTo("0.00425");
        var row=json.readTree(get("?limit=1",admin).body()).get(0);
        assertThat(row.path("priceVersion").asText()).isEqualTo("bailian-beijing-2026-09-04");
        assertThat(row.path("priceCurrency").asText()).isEqualTo("CNY");
        assertThat(row.path("priceSourceUrl").asText()).startsWith("https://help.aliyun.com/");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"endpoint","model","actual","usage","partial","range"})
    void unmatchedOrIncompleteCostsRemainUnknown(String kind) throws Exception {
        long id=priced("CHAT",kind.equals("model")?"unpriced":"qwen3.8-flash",kind.equals("endpoint")?"https://dashscope-intl.aliyuncs.com/compatible-mode/v1":BEIJING);
        var usage=switch(kind) {
            case "usage" -> null;
            case "partial" -> new ModelCallLog.Tokens(1000,null,1000);
            case "range" -> new ModelCallLog.Tokens(1000001,1,1000002);
            default -> new ModelCallLog.Tokens(1,1,2);
        };
        log.finish(id,"COMPLETED",kind.equals("actual")?"another-model":null,usage,1,null);
        assertThat(cost(id)).isNull();
        var summary=json.readTree(get("/summary",token("ADMIN")).body());
        assertThat(summary.path("estimatedCostCny").isNull()).isTrue();
        assertThat(summary.path("unknownCostCalls").asInt()).isEqualTo(1);
    }
    @Test void priceSnapshotSurvivesNewVersionAndTerminalReplay() {
        long first=priced("CHAT","qwen3.8-flash",BEIJING);
        jdbc.sql("""
                INSERT INTO model_price(version,call_type,endpoint,model,input_per_million,output_per_million,max_input_tokens,verified_at,source_url)
                SELECT 'test-new-version',call_type,endpoint,model,0.000001,0,max_input_tokens,CURRENT_DATE,source_url
                FROM model_price WHERE call_type='CHAT' AND version='bailian-beijing-2026-09-04'
                """).update();
        try {
            jdbc.sql("""
                    INSERT INTO model_price(version,call_type,endpoint,model,input_per_million,output_per_million,max_input_tokens,verified_at,source_url)
                    SELECT 'test-future-version',call_type,endpoint,model,99,99,max_input_tokens,DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),source_url
                    FROM model_price WHERE call_type='CHAT' AND version='bailian-beijing-2026-09-04'
                    """).update();
            long second=priced("CHAT","qwen3.8-flash",BEIJING);
            for(long id:List.of(first,second)) log.finish(id,"COMPLETED",null,new ModelCallLog.Tokens(1,1,2),1,null);
            assertThat(cost(first)).isEqualByComparingTo("0.0000035");
            assertThat(cost(second)).isEqualByComparingTo("0.000000000001");
            log.finish(first,"FAILED",null,new ModelCallLog.Tokens(100,100,200),1,"LateError");
            assertThat(cost(first)).isEqualByComparingTo("0.0000035");
        } finally { jdbc.sql("DELETE FROM model_price WHERE version IN ('test-new-version','test-future-version')").update(); }
    }

    @Test void budgetReservesAndSettlesBeforeAllowingAnotherCall() {
        var guarded=new ModelCallLog(jdbc,manager,true);
        long id=guarded.start(UUID.randomUUID().toString(),1,"CHAT","PRIMARY",false,null,"qwen3.8-flash",null,BEIJING);
        assertThat(jdbc.sql("SELECT held_cny FROM model_budget WHERE id=1").query(java.math.BigDecimal.class).single()).isGreaterThan(BigDecimal.ZERO);
        guarded.finish(id,"COMPLETED","qwen3.8-flash",new ModelCallLog.Tokens(1000,500,1500),1,null);
        assertThat(jdbc.sql("SELECT held_cny FROM model_budget WHERE id=1").query(java.math.BigDecimal.class).single()).isZero();
        assertThat(jdbc.sql("SELECT spent_cny FROM model_budget WHERE id=1").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.00215");
        guarded.finish(id,"FAILED","qwen3.8-flash",new ModelCallLog.Tokens(1000,500,1500),1,"late");
        assertThat(jdbc.sql("SELECT spent_cny FROM model_budget WHERE id=1").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.00215");
    }

    @Test void budgetRejectsBeforeCreatingAttemptWhenLimitIsInsufficient() {
        jdbc.sql("UPDATE model_budget SET limit_cny=0 WHERE id=1").update();
        var guarded=new ModelCallLog(jdbc,manager,true);
        assertThatThrownBy(() -> guarded.start(UUID.randomUUID().toString(),1,"CHAT","PRIMARY",false,null,"qwen3.8-flash",null,BEIJING))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call").query(Long.class).single()).isZero();
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
