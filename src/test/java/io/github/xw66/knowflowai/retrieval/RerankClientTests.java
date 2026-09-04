package io.github.xw66.knowflowai.retrieval;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class RerankClientTests {
    static final org.testcontainers.mysql.MySQLContainer database=new org.testcontainers.mysql.MySQLContainer("mysql:8.4.8");
    static org.springframework.jdbc.core.simple.JdbcClient jdbc;
    static io.github.xw66.knowflowai.observability.ModelCallLog log;
    @BeforeAll static void database() {
        database.start();
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        jdbc=org.springframework.jdbc.core.simple.JdbcClient.create(source);
        log=new io.github.xw66.knowflowai.observability.ModelCallLog(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    }
    @AfterAll static void closeDatabase() { database.stop(); }
    @BeforeEach void clear() { jdbc.sql("DELETE FROM model_call").update(); }
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<SearchService.Hit> hits=List.of(new SearchService.Hit(1,1,"文档","报销需要发票",2,1,0.03),
            new SearchService.Hit(2,1,"文档","请假需要审批",3,2,0.02));

    @Test void validatesProtocolAndKeepsOriginalEvidence() throws Exception {
        var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            count.incrementAndGet();
            var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only");
            assertThat(request.path("model").asText()).isEqualTo("test-rerank");
            assertThat(request.at("/parameters/top_n").asInt()).isEqualTo(2);
            assertThat(request.at("/parameters/return_documents").asBoolean()).isFalse();
            assertThat(request.at("/input/documents")).extracting(node -> node.asText()).containsExactly("报销需要发票","请假需要审批");
            var bytes=mapper.writeValueAsBytes(Map.of("usage",Map.of("total_tokens",31),"output",Map.of("results",List.of(
                    Map.of("index",0,"relevance_score",0.1,"document",Map.of("text","伪造原文")),
                    Map.of("index",1,"relevance_score",0.9)))));
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var client=client(server,Duration.ofSeconds(2))) {
            var result=client.rank("如何请假",hits);
            assertThat(result).extracting(SearchService.Hit::chunkId).containsExactly(2L,1L);
            assertThat(result.getFirst().content()).isEqualTo("请假需要审批");
            assertThat(result.getFirst().pageNumber()).isEqualTo(3);
            assertThat(result.getFirst().score()).isEqualTo(0.9);
            assertThat(count).hasValue(1);
            assertThat(jdbc.sql("SELECT total_tokens FROM model_call WHERE call_type='RERANK' AND status='COMPLETED' AND input_tokens IS NULL AND output_tokens IS NULL AND actual_model IS NULL").query(Integer.class).single()).isEqualTo(31);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings={"duplicate","outside","missing","fraction","score","stringScore","error","json","oversized"})
    void rejectsUntrustedPermutations(String variant) throws Exception {
        String response=switch(variant) {
            case "duplicate" -> "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":0,\"relevance_score\":0.4}]}}";
            case "outside" -> "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":2,\"relevance_score\":0.4}]}}";
            case "fraction" -> "{\"output\":{\"results\":[{\"index\":0.5,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.4}]}}";
            case "score" -> "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":1.1},{\"index\":1,\"relevance_score\":0.4}]}}";
            case "stringScore" -> "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":\"0.5\"},{\"index\":1,\"relevance_score\":0.4}]}}";
            case "error" -> "{\"code\":\"ModelUnavailable\"}";
            case "json" -> "not-json";
            case "oversized" -> "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.4}]},\"padding\":\""+"x".repeat(131072)+"\"}";
            default -> "{\"output\":{\"results\":[]}}";
        };
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();
            String payload=response.startsWith("{")?response.replaceFirst("\\{","{\"usage\":{\"total_tokens\":9},"):response;
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var client=client(server,Duration.ofSeconds(2))) {
            assertThatThrownBy(()->client.rank("问题",hits)).isInstanceOfAny(java.io.IOException.class,RuntimeException.class);
            assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("FAILED");
            assertThat(jdbc.sql("SELECT usage_known FROM model_call").query(Boolean.class).single()).isEqualTo(!List.of("json","oversized").contains(variant));
        } finally { server.stop(0); }
    }

    @Test void deadlineIncludesSlowResponseBodyAndDoesNotRetry() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var client=client(server,Duration.ofMillis(250))) {
            long start=System.nanoTime();
            assertThatThrownBy(()->client.rank("问题",hits)).isInstanceOf(java.io.IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
            assertThat(calls).hasValue(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='FAILED' AND total_tokens IS NULL AND NOT usage_known").query(Long.class).single()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    private RerankClient client(HttpServer server,Duration timeout) {
        return new RerankClient("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","test-only","test-rerank",timeout,20,mapper,log);
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","null","-1","1.5","\"7\"","2147483648","0"})
    void usageMetadataDoesNotInventTokens(String value) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange-> {
            exchange.getRequestBody().readAllBytes();
            String usage=value.equals("missing")?"":"\"usage\":{\"total_tokens\":"+value+"},";
            byte[] body=("{"+usage+"\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.1}]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try(var client=client(server,Duration.ofSeconds(2))) {
            assertThat(client.rank("问题",hits)).hasSize(2);
            if(value.equals("0")) assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='COMPLETED' AND total_tokens=0 AND usage_known").query(Long.class).single()).isEqualTo(1);
            else assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='COMPLETED' AND total_tokens IS NULL AND NOT usage_known").query(Long.class).single()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test void unavailableLedgerPreventsHttpRequest() throws Exception {
        var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{ count.incrementAndGet(); exchange.sendResponseHeaders(503,-1); exchange.close(); });
        server.start();
        jdbc.sql("RENAME TABLE model_call TO model_call_unavailable").update();
        try(var client=client(server,Duration.ofSeconds(2))) {
            assertThatThrownBy(()->client.rank("问题",hits)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(count).hasValue(0);
        } finally { jdbc.sql("RENAME TABLE model_call_unavailable TO model_call").update(); server.stop(0); }
    }
}
