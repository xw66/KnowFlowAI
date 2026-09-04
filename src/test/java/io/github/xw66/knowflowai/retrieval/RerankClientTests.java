package io.github.xw66.knowflowai.retrieval;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class RerankClientTests {
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
            var bytes=mapper.writeValueAsBytes(Map.of("output",Map.of("results",List.of(
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
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var client=client(server,Duration.ofSeconds(2))) {
            assertThatThrownBy(()->client.rank("问题",hits)).isInstanceOfAny(java.io.IOException.class,RuntimeException.class);
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
        } finally { server.stop(0); }
    }

    private RerankClient client(HttpServer server,Duration timeout) {
        return new RerankClient("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","test-only","test-rerank",timeout,20,mapper);
    }
}
