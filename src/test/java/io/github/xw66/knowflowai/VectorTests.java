package io.github.xw66.knowflowai;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import com.sun.net.httpserver.HttpServer;
import io.github.xw66.knowflowai.ingestion.VectorTaskProcessor;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.web-application-type=servlet", "app.outbox.initial-delay=3600000",
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.embedding.enabled=true", "app.embedding.api-key=test-only", "app.embedding.model=test-embedding",
        "app.embedding.dimensions=3", "app.vector.initial-delay=3600000", "app.processing.initial-delay=3600000",
        "app.cleanup.initial-delay=3600000",
        "app.bm25.enabled=true", "app.bm25.initial-delay=3600000",
        "app.chat.enabled=true", "app.chat.api-key=test-only", "app.chat.model=test-chat", "app.chat.timeout=PT20S",
        "app.chat.stream-deadline=PT8S",
        "app.model-budget.enabled=false",
        "app.cache.enabled=true",
        "app.idempotency.enabled=true",
        "app.rate-limit.enabled=true", "app.rate-limit.search=10000", "app.rate-limit.answer=10000",
        "app.rerank.enabled=true", "app.rerank.api-key=test-only", "app.rerank.model=test-rerank", "app.rerank.timeout=PT2S",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@ActiveProfiles({"worker", "api"})
@Import({KnowFlowAiApplicationTests.DatabaseConfiguration.class, KnowledgeBaseTests.RedisConfiguration.class})
class VectorTests {
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.18.2").withExposedPorts(6333);
    static HttpServer server;
    static volatile int responseStatus = 200;
    static volatile int responseDimensions = 3;
    static volatile String requestedPath;
    static volatile int calls;
    static volatile Runnable modelHook = () -> {};
    static volatile Runnable rerankHook=() -> {};
    static volatile int rerankStatus=200;
    static volatile int rerankCalls;
    static volatile JsonNode rerankRequest;
    static volatile JsonNode chatRequest;
    static volatile String chatOverride;
    static volatile String chatFinish="stop";
    static volatile Runnable chatHook=() -> {};
    static volatile int chatCalls;
    static volatile int chatStatus=200;
    static volatile boolean chatUsage=true;
    static volatile boolean streamOmitFinish;
    static volatile boolean streamHold;
    static volatile boolean streamDisconnected;
    static volatile Runnable streamHook=()->{};
    static volatile int rewriteCalls;
    static volatile int rewriteStatus=200;
    static volatile String rewriteOutput="{\"query\":\"报销由谁负责？\"}";
    static volatile Runnable rewriteHook=()->{};
    static volatile JsonNode rewriteRequest;
    static final ObjectMapper JSON = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path documentDirectory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("app.document.storage-directory", () -> documentDirectory.toString());
        registry.add("app.bm25.directory", () -> documentDirectory.resolve("lucene").toString());
        QDRANT.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions",exchange -> {
            chatCalls++;
            chatRequest=JSON.readTree(exchange.getRequestBody().readAllBytes());
            boolean rewriting=JSON.readTree(chatRequest.at("/messages/1/content").asText()).has("previousQuestion");
            if(rewriting) { rewriteCalls++; rewriteRequest=chatRequest; rewriteHook.run(); }
            if(chatRequest.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(chatStatus,0);
                try {
                    String text=chatOverride==null?"原文说明[C1]":chatOverride;
                    var output=exchange.getResponseBody();
                    for(String part:java.util.List.of(text.substring(0,Math.min(2,text.length())),text.substring(Math.min(2,text.length())))) {
                        output.write(streamChunk(part,null).getBytes(StandardCharsets.UTF_8)); output.flush();
                        streamHook.run();
                        while(streamHold) {
                            output.write(": waiting\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                            try { Thread.sleep(100); } catch(InterruptedException error) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                    if(!streamOmitFinish) {
                        output.write(streamChunk("",chatFinish).getBytes(StandardCharsets.UTF_8));
                        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                } catch(java.io.IOException error) { streamDisconnected=true; }
                finally { exchange.close(); }
                return;
            }
            var evidence=JSON.readTree(chatRequest.at("/messages/1/content").asText()).at("/evidence/0");
            String content=rewriting ? rewriteOutput : chatOverride!=null ? chatOverride : JSON.writeValueAsString(Map.of("answer","原文说明[C1]",
                    "citations",java.util.List.of(Map.of("id","C1","quote",evidence.path("content").asText()))));
            if(!rewriting) chatHook.run();
            var result=new java.util.LinkedHashMap<String,Object>();
            result.put("id","test-answer"); result.put("object","chat.completion"); result.put("created",1); result.put("model","test-chat");
            result.put("choices",java.util.List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason",chatFinish)));
            if(chatUsage) result.put("usage",Map.of("prompt_tokens",20,"completion_tokens",10,"total_tokens",30));
            byte[] body=JSON.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(rewriting?rewriteStatus:chatStatus,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/rerank",exchange -> {
            rerankCalls++;
            rerankRequest=JSON.readTree(exchange.getRequestBody().readAllBytes());
            rerankHook.run();
            int count=rerankRequest.at("/input/documents").size();
            var results=new ArrayList<Map<String,Object>>();
            for(int i=0;i<count;i++) results.add(Map.of("index",i,"relevance_score",(i+1.0)/(count+1),"document",Map.of("text","伪造原文")));
            byte[] body=JSON.writeValueAsBytes(Map.of("output",Map.of("results",results),"usage",Map.of("total_tokens",count)));
            exchange.sendResponseHeaders(rerankStatus,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        // 仅验证兼容协议与状态机，固定测试向量没有语义能力，不能用于检索评测。
        server.createContext("/", exchange -> {
            calls++;
            modelHook.run();
            requestedPath = exchange.getRequestURI().getPath();
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            var data = new ArrayList<Map<String, Object>>();
            int count = request.path("input").isArray() ? request.path("input").size() : 1;
            for (int i = 0; i < count; i++) {
                var vector = new ArrayList<Double>();
                for (int d = 0; d < responseDimensions; d++) vector.add(0.1 + d);
                data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
            }
            int status=count>10?400:responseStatus;
            byte[] body = JSON.writeValueAsBytes(status == 200
                    ? Map.of("object", "list", "model", "test-embedding", "data", data, "usage", Map.of("prompt_tokens", count, "total_tokens", count))
                    : Map.of("error", Map.of("message", "test failure", "type", "server_error")));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        registry.add("app.embedding.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        registry.add("app.chat.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        registry.add("app.rerank.url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/rerank");
        registry.add("app.qdrant.url", () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
    }

    @AfterAll static void stop() { server.stop(0); QDRANT.stop(); }
    private static String streamChunk(String text,String finish) {
        var choice=new java.util.LinkedHashMap<String,Object>();
        choice.put("index",0); choice.put("delta",Map.of("role","assistant","content",text)); choice.put("finish_reason",finish);
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("id","test-stream"); result.put("object","chat.completion.chunk"); result.put("created",1); result.put("model","test-chat");
        result.put("choices",java.util.List.of(choice));
        if(finish!=null && chatUsage) result.put("usage",Map.of("prompt_tokens",20,"completion_tokens",10,"total_tokens",30));
        return "data: "+JSON.writeValueAsString(result)+"\n\n";
    }
    @Autowired JdbcClient jdbc;
    @Autowired VectorTaskProcessor processor;
    @Autowired QdrantIndex index;
    @Autowired io.github.xw66.knowflowai.observability.ExternalReconcileController reconciliation;
    @Autowired io.github.xw66.knowflowai.ingestion.Bm25TaskProcessor bm25;
    @Autowired io.github.xw66.knowflowai.retrieval.LuceneIndex lexicalIndex;
    @Autowired io.github.xw66.knowflowai.ingestion.VectorCleanupProcessor cleanup;
    @Autowired io.github.xw66.knowflowai.document.DocumentService documents;
    @Autowired io.github.xw66.knowflowai.ingestion.TextTaskProcessor parser;
    @Autowired io.github.xw66.knowflowai.retrieval.SearchService search;
    @Autowired io.github.xw66.knowflowai.chat.ConversationService conversations;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    private java.net.http.HttpResponse<String> answer(long task) throws Exception {
        return request(base(task),owner(task),"{\"question\":\"测试段落\"}","answers");
    }

    private java.net.http.HttpResponse<String> history(long conversation,long user) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(buildRequest(1,user,"{}",""),(name,value)->true)
                .uri(java.net.URI.create("http://localhost:"+port+"/api/conversations/"+conversation+"/messages")).GET().build();
        try(var client=java.net.http.HttpClient.newHttpClient()) { return client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()); }
    }
    private String messageStatus(long task) {
        return jdbc.sql("SELECT m.status FROM chat_message m JOIN conversation c ON c.id=m.conversation_id WHERE c.knowledge_base_id=:id AND m.role='ASSISTANT' ORDER BY m.id DESC LIMIT 1")
                .param("id",base(task)).query(String.class).single();
    }

    private long rewriteFixture() {
        long task=seed(1);
        jdbc.sql("UPDATE document_chunk c JOIN document_task t ON t.document_id=c.document_id SET c.content='报销材料提交财务经理。' WHERE t.id=:id").param("id",task).update();
        processor.processNext(); indexAllBm25();
        return task;
    }
    private long startRewriteConversation(long task) throws Exception {
        var response=request(base(task),owner(task),"{\"question\":\"报销材料\",\"mode\":\"BM25\"}","answers");
        assertThat(response.statusCode()).isEqualTo(200);
        return Long.parseLong(response.headers().firstValue("X-Conversation-Id").orElseThrow());
    }
    private java.net.http.HttpResponse<String> followup(long task,long conversation,String endpoint) throws Exception {
        return request(base(task),owner(task),"{\"question\":\"该找谁\",\"mode\":\"BM25\",\"rewrite\":true,\"conversationId\":"+conversation+"}",endpoint);
    }

    @Test
    void rewriteResolvesFollowupAndRecordsOriginalEffectiveQueryAndUsage() throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        assertThat(search.search(owner(task),base(task),"该找谁",4,io.github.xw66.knowflowai.retrieval.SearchService.Mode.BM25)).isEmpty();
        int before=rewriteCalls;
        var response=followup(task,conversation,"answers");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("ANSWERED");
        assertThat(rewriteCalls).isEqualTo(before+1);
        assertThat(rewriteRequest.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(rewriteRequest.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(rewriteRequest.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(JSON.readTree(rewriteRequest.at("/messages/1/content").asText()).path("previousQuestion").asText()).isEqualTo("报销材料");
        var saved=conversations.messages(owner(task),conversation,0,50).getLast();
        assertThat(saved.originalQuestion()).isEqualTo("该找谁");
        assertThat(saved.retrievalQuery()).isEqualTo("报销由谁负责？");
        assertThat(saved.rewriteStatus()).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT rewrite_total_tokens FROM chat_message WHERE id=:id").param("id",saved.id()).query(Integer.class).single()).isEqualTo(30);
        assertThat(jdbc.sql("SELECT total_tokens FROM model_call WHERE message_id=:id AND call_type='REWRITE' AND status='COMPLETED'").param("id",saved.id()).query(Integer.class).single()).isEqualTo(30);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE message_id=:id AND status='COMPLETED'").param("id",saved.id()).query(Long.class).single()).isEqualTo(2));
    }

    @Test
    void sseMissingUsageIsUnknownInMessageAndLedger() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        chatUsage=false;
        try {
            var response=request(base(task),owner(task),"{\"question\":\"测试\"}","answers/stream");
            assertThat(response.body()).contains("event:done").doesNotContain("event:error");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call m JOIN chat_message c ON c.id=m.message_id JOIN conversation v ON v.id=c.conversation_id WHERE v.knowledge_base_id=:base AND m.status='COMPLETED' AND NOT m.usage_known AND m.total_tokens IS NULL AND c.total_tokens IS NULL").param("base",base(task)).query(Long.class).single()).isEqualTo(1));
        } finally { chatUsage=true; }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ERROR","INVALID","EMPTY","EXTRA","LONG"})
    void rewriteFailureFallsBackWithoutExtraAnswerCall(String kind) throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        int before=rewriteCalls,chats=chatCalls;
        if(kind.equals("ERROR")) rewriteStatus=503;
        else rewriteOutput=switch(kind) {
            case "INVALID" -> "bad json";
            case "EMPTY" -> "{\"query\":\"\"}";
            case "EXTRA" -> "{\"query\":\"报销\",\"knowledgeBaseId\":999}";
            default -> JSON.writeValueAsString(Map.of("query","长".repeat(2001)));
        };
        try {
            var response=followup(task,conversation,"answers");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("INSUFFICIENT_EVIDENCE");
            var saved=conversations.messages(owner(task),conversation,0,50).getLast();
            assertThat(saved.retrievalQuery()).isEqualTo("该找谁");
            assertThat(saved.rewriteStatus()).isEqualTo("FALLBACK");
            assertThat(jdbc.sql("SELECT status FROM model_call WHERE message_id=:id AND call_type='REWRITE'").param("id",saved.id()).query(String.class).single()).isEqualTo(kind.equals("ERROR")?"FAILED":"COMPLETED");
            assertThat(jdbc.sql("SELECT usage_known FROM model_call WHERE message_id=:id AND call_type='REWRITE'").param("id",saved.id()).query(Boolean.class).single()).isEqualTo(!kind.equals("ERROR"));
            assertThat(rewriteCalls).isEqualTo(before+1);
            assertThat(chatCalls).isEqualTo(chats+1);
        } finally { rewriteStatus=200; rewriteOutput="{\"query\":\"报销由谁负责？\"}"; }
    }

    @Test
    void rewriteSkipsFirstQuestionAndInvalidHistory() throws Exception {
        long task=rewriteFixture(); int before=rewriteCalls;
        var first=request(base(task),owner(task),"{\"question\":\"报销\",\"rewrite\":true,\"mode\":\"BM25\"}","answers");
        long conversation=Long.parseLong(first.headers().firstValue("X-Conversation-Id").orElseThrow());
        assertThat(conversations.messages(owner(task),conversation,0,50).getLast().rewriteStatus()).isEqualTo("NO_CONTEXT");
        jdbc.sql("UPDATE document SET status='DELETED' WHERE knowledge_base_id=:id").param("id",base(task)).update();
        assertThat(followup(task,conversation,"answers").statusCode()).isEqualTo(200);
        assertThat(rewriteCalls).isEqualTo(before);
        assertThat(conversations.messages(owner(task),conversation,0,50).getLast().rewriteStatus()).isEqualTo("NO_CONTEXT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call m JOIN chat_message c ON c.id=m.message_id WHERE c.conversation_id=:id AND m.call_type='REWRITE'").param("id",conversation).query(Long.class).single()).isZero();
    }

    @Test
    void rewriteRechecksPermissionsAfterModel() throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        rewriteHook=()->jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base(task)).update();
        try { assertThat(followup(task,conversation,"answers").statusCode()).isEqualTo(404); }
        finally { rewriteHook=()->{}; }
    }

    @Test
    void rewrittenTextCannotExpandKnowledgeBaseScope() throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        long other=seed(1);
        jdbc.sql("UPDATE document_chunk c JOIN document_task t ON t.document_id=c.document_id SET c.content='隔离机密专属资料' WHERE t.id=:id")
                .param("id",other).update();
        processor.processNext(); indexAllBm25();
        assertThat(search.search(owner(other),base(other),"隔离机密",4,io.github.xw66.knowflowai.retrieval.SearchService.Mode.BM25)).hasSize(1);
        rewriteOutput="{\"query\":\"隔离机密\"}";
        try {
            var response=followup(task,conversation,"answers");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("INSUFFICIENT_EVIDENCE").doesNotContain("专属资料");
        } finally { rewriteOutput="{\"query\":\"报销由谁负责？\"}"; }
    }

    @Test
    void rewriteTimeoutFallsBackBeforeChatDefaultTimeout() throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        var release=new java.util.concurrent.CountDownLatch(1);
        rewriteHook=()-> {
            try { release.await(8,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(()->followup(task,conversation,"answers"));
            var response=future.get(6,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(conversations.messages(owner(task),conversation,0,50).getLast().rewriteStatus()).isEqualTo("FALLBACK");
        } finally { release.countDown(); rewriteHook=()->{}; }
    }

    @Test
    void rewriteSourceRemainsProtectedAcrossMultipleTurnsAndStreaming() throws Exception {
        long task=rewriteFixture(),conversation=startRewriteConversation(task);
        long other=seed(1);
        jdbc.sql("UPDATE document d JOIN document_task t ON t.document_id=d.id SET d.knowledge_base_id=:base WHERE t.id=:id")
                .param("base",base(task)).param("id",other).update();
        jdbc.sql("UPDATE document_chunk c JOIN document_task t ON t.document_id=c.document_id SET c.content='财务负责人是李经理。' WHERE t.id=:id")
                .param("id",other).update();
        processor.processNext(); indexAllBm25();
        rewriteOutput="{\"query\":\"财务负责人\"}";
        try {
            var response=followup(task,conversation,"answers/stream");
            assertThat(response.body()).contains("APPLIED","event:done").doesNotContain("event:error");
            assertThat(followup(task,conversation,"answers").statusCode()).isEqualTo(200);
            var last=conversations.messages(owner(task),conversation,0,50).getLast();
            assertThat(last.citations()).hasSize(1).allMatch(c->c.quote().contains("李经理"));
            jdbc.sql("UPDATE document_chunk c JOIN document_task t ON t.document_id=c.document_id SET c.content='changed' WHERE t.id=:id").param("id",task).update();
            var messages=conversations.messages(owner(task),conversation,0,50);
            assertThat(messages.stream().filter(m->m.role().equals("ASSISTANT")).toList()).hasSize(3)
                    .allMatch(m->m.redacted() && m.content()==null && m.retrievalQuery()==null);
        } finally { rewriteOutput="{\"query\":\"报销由谁负责？\"}"; }
    }

    @Test
    void conversationPersistsAnswersAndOnlyItsOwnerCanReadOrContinue() throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        var response=answer(task);
        assertThat(response.statusCode()).isEqualTo(200);
        long conversation=Long.parseLong(response.headers().firstValue("X-Conversation-Id").orElseThrow());
        var saved=history(conversation,owner(task));
        assertThat(saved.statusCode()).isEqualTo(200);
        var messages=JSON.readTree(saved.body());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(messages.get(1).path("content").asText()).isEqualTo(JSON.readTree(response.body()).path("answer").asText());
        assertThat(messages.get(1).path("citations")).hasSize(1);
        assertThat(messages.get(1).at("/usage/totalTokens").asInt()).isEqualTo(30);
        long messageId=Long.parseLong(response.headers().firstValue("X-Message-Id").orElseThrow());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE message_id=:message AND status='COMPLETED' AND total_tokens=30 AND NOT streaming").param("message",messageId).query(Long.class).single()).isEqualTo(1));
        long other=seed(1); processor.processNext();
        assertThat(history(conversation,owner(other)).statusCode()).isEqualTo(404);
        assertThat(request(base(other),owner(other),"{\"question\":\"测试\",\"conversationId\":"+conversation+"}","answers").statusCode()).isEqualTo(404);
        assertThat(request(base(task),owner(task),"{\"question\":\"继续\",\"conversationId\":"+conversation+"}","answers").statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(history(conversation,owner(task)).body())).hasSize(4);
        assertThat(conversations.list(owner(task),0,100)).anyMatch(item->item.id()==conversation);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DELETE","VERSION","UNCITED","REVOKE"})
    void conversationHistoryRechecksEveryInputSource(String change) throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        var response=answer(task);
        long conversation=Long.parseLong(response.headers().firstValue("X-Conversation-Id").orElseThrow());
        long base=base(task),user=owner(task);
        switch(change) {
            case "DELETE" -> jdbc.sql("UPDATE document SET status='DELETED' WHERE knowledge_base_id=:id").param("id",base).update();
            case "VERSION" -> jdbc.sql("UPDATE document SET active_index_version=2 WHERE knowledge_base_id=:id").param("id",base).update();
            case "REVOKE" -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base).update();
            default -> {
                long chunk=jdbc.sql("SELECT chunk_id FROM message_citation s JOIN chat_message m ON m.id=s.message_id WHERE m.conversation_id=:id AND s.cited=FALSE")
                        .param("id",conversation).query(Long.class).single();
                jdbc.sql("UPDATE document_chunk SET content='changed' WHERE id=:id").param("id",chunk).update();
            }
        }
        var result=history(conversation,user);
        if(change.equals("REVOKE")) {
            assertThat(result.statusCode()).isEqualTo(404);
            assertThat(conversations.list(user,0,100)).noneMatch(item->item.id()==conversation);
        } else {
            assertThat(result.statusCode()).isEqualTo(200);
            var assistant=JSON.readTree(result.body()).get(1);
            assertThat(assistant.path("redacted").asBoolean()).isTrue();
            assertThat(assistant.path("content").isNull()).isTrue();
            assertThat(assistant.path("citations")).isEmpty();
        }
    }

    @Test
    void conversationRejectsConcurrentGenerationAndRecoversInterruptedTurns() {
        long task=seed(1); processor.processNext();
        var turn=conversations.begin(owner(task),base(task),null,"测试");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->conversations.begin(owner(task),base(task),turn.conversationId(),"重复"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(409));
        jdbc.sql("UPDATE chat_message SET created_at=TIMESTAMPADD(MINUTE,-6,CURRENT_TIMESTAMP(6)) WHERE id=:id").param("id",turn.messageId()).update();
        var messages=conversations.messages(owner(task),turn.conversationId(),0,50);
        assertThat(messages.getLast().status()).isEqualTo("FAILED");
        assertThat(messages.getLast().errorCode()).isEqualTo("PROCESS_INTERRUPTED");
        var next=conversations.begin(owner(task),base(task),turn.conversationId(),"重试");
        conversations.terminate(next,"CANCELLED","部分内容",null,null,"CLIENT_DISCONNECTED");
        conversations.terminate(next,"FAILED","不能覆盖",null,null,"LATE_FAILURE");
        assertThat(conversations.messages(owner(task),turn.conversationId(),0,50).getLast().content()).isEqualTo("部分内容");
    }

    @Test
    void conversationCompletionRollsBackIfCitationDoesNotMatch() {
        long task=seed(1); processor.processNext();
        var turn=conversations.begin(owner(task),base(task),null,"测试");
        var hit=search.search(owner(task),base(task),"测试",1).getFirst();
        conversations.evidence(turn,Map.of("C1",hit));
        var forged=new io.github.xw66.knowflowai.chat.AnswerService.Citation("C1",hit.chunkId(),hit.documentId(),hit.documentName(),null,1,"伪造摘录");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->conversations.complete(turn,"正文",java.util.List.of(forged),null,null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(messageStatus(task)).isEqualTo("RUNNING");
        conversations.terminate(turn,"FAILED","",null,null,"TEST_FAILED");
    }

    @Test
    void simultaneousFollowupsCreateOnlyOneRunningAnswer() throws Exception {
        long task=seed(1); processor.processNext();
        var initial=conversations.begin(owner(task),base(task),null,"测试");
        conversations.terminate(initial,"CANCELLED","",null,null,"TEST");
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> attempt=()-> {
                barrier.await();
                try { conversations.begin(owner(task),base(task),initial.conversationId(),"并发追问"); return 200; }
                catch(org.springframework.web.server.ResponseStatusException error) { return error.getStatusCode().value(); }
            };
            var first=executor.submit(attempt); var second=executor.submit(attempt);
            assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200,409);
        }
    }

    @Test
    void sseStreamsBodyAndFinishesWithVerifiedCitations() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        var response=request(base(task),owner(task),"{\"question\":\"测试\"}","answers/stream");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.body()).contains("event:metadata","event:delta","event:citation","event:usage","event:done","test.txt","ANSWERED").doesNotContain("event:error");
        assertThat(response.body().indexOf("event:delta")).isLessThan(response.body().indexOf("event:citation"));
        assertThat(response.body().indexOf("event:citation")).isLessThan(response.body().indexOf("event:done"));
        assertThat(chatRequest.path("stream").asBoolean()).isTrue();
        assertThat(chatRequest.at("/stream_options/include_usage").asBoolean()).isTrue();
        assertThat(chatRequest.at("/response_format/type").asText()).isEqualTo("text");
        assertThat(chatRequest.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(chatRequest.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(messageStatus(task)).isEqualTo("COMPLETED");
        assertThat(response.body()).contains("conversationId","messageId");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call m JOIN chat_message c ON c.id=m.message_id JOIN conversation v ON v.id=c.conversation_id WHERE v.knowledge_base_id=:base AND m.streaming AND m.status='COMPLETED' AND m.total_tokens=30").param("base",base(task)).query(Long.class).single()).isEqualTo(1));
        assertThat(redis.opsForValue().get("knowflow:rate:v1:ANSWER:" + owner(task))).isEqualTo("1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TRUNCATED","EOF","FAKE","REVOKE"})
    void sseErrorsNeverMarkPartialOutputSuccessful(String failure) throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        if(failure.equals("TRUNCATED")) chatFinish="length";
        if(failure.equals("EOF")) streamOmitFinish=true;
        if(failure.equals("FAKE")) chatOverride="伪造[C99]";
        if(failure.equals("REVOKE")) streamHook=()->jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base(task)).update();
        try {
            var response=request(base(task),owner(task),"{\"question\":\"测试\"}","answers/stream");
            assertThat(response.body()).contains("event:error").doesNotContain("event:done","event:citation");
            assertThat(messageStatus(task)).isEqualTo("FAILED");
            if(failure.equals("REVOKE")) assertThat(response.body()).contains("\"discard\":true");
        } finally { chatFinish="stop"; streamOmitFinish=false; chatOverride=null; streamHook=()->{}; }
    }

    @Test
    void sseNoEvidenceSkipsModel() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        int before=chatCalls;
        var response=request(base(task),owner(task),"{\"question\":\"zzzxxyy\",\"mode\":\"BM25\"}","answers/stream");
        assertThat(response.body()).contains("event:done","INSUFFICIENT_EVIDENCE").doesNotContain("event:delta","event:error");
        assertThat(chatCalls).isEqualTo(before);
    }

    @Test
    void sseDeliversBeforeModelFinishesAndDisconnectCancelsUpstream() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        streamHold=true; streamDisconnected=false;
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var response=client.send(buildRequest(base(task),owner(task),"{\"question\":\"测试\"}","answers/stream"),java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                var first=java.util.concurrent.CompletableFuture.supplyAsync(()-> {
                    try { String line; while((line=reader.readLine())!=null) if(line.equals("event:delta")) return true; return false; }
                    catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                });
                assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(streamHold).isTrue();
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(6)).until(()->streamDisconnected);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->messageStatus(task).equals("CANCELLED"));
        } finally { streamHold=false; }
    }

    @Test
    void sseDeadlineCancelsAnUnfinishedModel() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        streamHold=true; streamDisconnected=false;
        try {
            var response=request(base(task),owner(task),"{\"question\":\"测试\"}","answers/stream");
            assertThat(response.body()).contains("event:delta","event:error","STREAM_TIMEOUT").doesNotContain("event:done");
            assertThat(messageStatus(task)).isEqualTo("FAILED");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->streamDisconnected);
        } finally { streamHold=false; }
    }

    @Test
    void answerReturnsVerifiedCitationsAndActualUsage() throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        var response=answer(task);
        assertThat(response.statusCode()).isEqualTo(200);
        var body=JSON.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(body.at("/citations/0/documentName").asText()).isEqualTo("test.txt");
        assertThat(body.at("/citations/0/quote").asText()).startsWith("测试段落");
        assertThat(body.at("/usage/totalTokens").asInt()).isEqualTo(30);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(chatRequest.path("messages")).hasSize(2);
        assertThat(chatRequest.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(chatRequest.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(chatRequest.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(chatRequest.path("max_tokens").asInt()).isEqualTo(512);
        chatUsage=false;
        try { assertThat(JSON.readTree(answer(task).body()).path("usage").isNull()).isTrue(); }
        finally { chatUsage=true; }
    }

    @Test
    void noEvidenceAndInvalidAccessAvoidChatCosts() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        int before=chatCalls;
        var response=request(base(task),owner(task),"{\"question\":\"zzzxxyyqq\",\"mode\":\"BM25\"}","answers");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(request(base(task),0,"{\"question\":\"测试\"}","answers").statusCode()).isEqualTo(401);
        assertThat(request(base(task),owner(task),"{\"question\":\"测试\",\"topK\":9}","answers").statusCode()).isEqualTo(400);
        long other=seed(1); processor.processNext();
        assertThat(request(base(task),owner(other),"{\"question\":\"测试\"}","answers").statusCode()).isEqualTo(404);
        assertThat(chatCalls).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "not json",
            "{\"answer\":\"伪造[C99]\",\"citations\":[{\"id\":\"C99\",\"quote\":\"测试段落0\"}]}",
            "{\"answer\":\"伪造[C1]\",\"citations\":[{\"id\":\"C1\",\"quote\":\"伪造原文\"}]}",
            "{\"answer\":\"缺失[C1]\",\"citations\":[]}",
            "{\"answer\":\"正文\",\"citations\":[],\"extra\":1}",
            "{\"answer\":\"正文\",\"citations\":[]} {}"})
    void invalidModelEvidenceIsNeverReturned(String output) throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        chatOverride=output;
        try {
            var response=answer(task);
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body()).doesNotContain("伪造","test-only");
            assertThat(messageStatus(task)).isEqualTo("FAILED");
        } finally { chatOverride=null; }
    }

    @Test
    void refusalTruncationAndUpstreamFailureHaveDistinctResults() throws Exception {
        assertThat(context.getEnvironment().getProperty("app.chat.fallback.enabled")).isEqualTo("false");
        long task=seed(1); processor.processNext(); indexAllBm25();
        chatOverride="{\"answer\":\"未经证实的事实\",\"citations\":[]}";
        try {
            var response=answer(task);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("INSUFFICIENT_EVIDENCE").doesNotContain("未经证实");
        } finally { chatOverride=null; }
        chatFinish="length";
        try { assertThat(answer(task).statusCode()).isEqualTo(502); }
        finally { chatFinish="stop"; }
        int before=chatCalls; chatStatus=503;
        try { assertThat(answer(task).statusCode()).isEqualTo(503); }
        finally { chatStatus=200; }
        assertThat(chatCalls).isEqualTo(before+1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REVOKE","DELETE","VERSION","UNCITED"})
    void answerRechecksAllInputEvidenceAfterGeneration(String change) throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        long base=base(task),user=owner(task);
        chatHook=() -> {
            switch(change) {
                case "REVOKE" -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base).update();
                case "DELETE" -> jdbc.sql("UPDATE document SET status='DELETED' WHERE knowledge_base_id=:id").param("id",base).update();
                case "VERSION" -> jdbc.sql("UPDATE document SET active_index_version=2 WHERE knowledge_base_id=:id").param("id",base).update();
                default -> {
                    String uncited=JSON.readTree(chatRequest.at("/messages/1/content").asText()).at("/evidence/1/content").asText();
                    jdbc.sql("UPDATE document_chunk c JOIN document d ON d.id=c.document_id SET c.content='changed' WHERE d.knowledge_base_id=:id AND c.content=:content")
                            .param("id",base).param("content",uncited).update();
                }
            }
        };
        try {
            var response=answer(task);
            assertThat(response.statusCode()).isEqualTo(change.equals("REVOKE")?404:409);
            assertThat(response.body()).doesNotContain("原文说明","测试段落");
        } finally { chatHook=() -> {}; }
    }

    @Test
    void rerankUsesFusionCandidatesBeforeTopKAndReportsActualOutcome() throws Exception {
        long task=seed(3); processor.processNext(); indexAllBm25();
        long last=jdbc.sql("SELECT COALESCE(MAX(id),0) FROM model_call").query(Long.class).single();
        int before=rerankCalls;
        var ordinary=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}");
        assertThat(ordinary.headers().firstValue("X-Rerank-Status")).contains("NOT_REQUESTED");
        assertThat(rerankCalls).isEqualTo(before);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE id>:last AND call_type='RERANK'").param("last",last).query(Long.class).single()).isZero();
        var response=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\",\"topK\":1,\"rerank\":true}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Rerank-Status")).contains("APPLIED");
        assertThat(response.headers().firstValue("X-Rerank-Model")).contains("test-rerank");
        assertThat(response.headers().firstValue("X-Search-Score-Type")).contains("RERANK");
        assertThat(rerankRequest.at("/input/documents")).hasSize(3);
        assertThat(JSON.readTree(response.body())).hasSize(1);
        assertThat(JSON.readTree(response.body()).get(0).path("content").asText()).isEqualTo(rerankRequest.at("/input/documents/2").asText());
        assertThat(response.body()).doesNotContain("伪造原文");
        assertThat(rerankCalls).isEqualTo(before+1);
        assertThat(jdbc.sql("SELECT total_tokens FROM model_call WHERE id>:last AND call_type='RERANK' AND status='COMPLETED' AND input_tokens IS NULL AND output_tokens IS NULL").param("last",last).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void rerankFailureReturnsOriginalRrfOrderAndInvalidModeDoesNotCallModels() throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        var baseline=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}");
        int before=rerankCalls;
        rerankStatus=503;
        try {
            var response=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\",\"rerank\":true}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(baseline.body());
            assertThat(response.headers().firstValue("X-Rerank-Status")).contains("FALLBACK");
            assertThat(response.headers().firstValue("X-Search-Score-Type")).contains("RRF");
            assertThat(response.headers().firstValue("X-Rerank-Model")).isEmpty();
        } finally { rerankStatus=200; }
        assertThat(rerankCalls).isEqualTo(before+1);
        int embeddings=calls;
        assertThat(request(base(task),owner(task),"{\"query\":\"测试\",\"mode\":\"VECTOR\",\"rerank\":true}").statusCode()).isEqualTo(400);
        assertThat(calls).isEqualTo(embeddings);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REVOKE","DELETE","VERSION"})
    void rerankRechecksAccessAndActiveVersionAfterRemoteCall(String change) throws Exception {
        long task=seed(2); processor.processNext(); indexAllBm25();
        long base=base(task),user=owner(task);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        rerankHook=() -> {
            switch(change) {
                case "REVOKE" -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:id").param("id",base).update();
                case "DELETE" -> documents.delete(user,base,document);
                default -> jdbc.sql("UPDATE document SET index_version=2,active_index_version=2 WHERE id=:id").param("id",document).update();
            }
        };
        try {
            var response=request(base,user,"{\"query\":\"测试段落\",\"mode\":\"HYBRID\",\"rerank\":true}");
            if(change.equals("REVOKE")) assertThat(response.statusCode()).isEqualTo(404);
            else { assertThat(response.statusCode()).isEqualTo(200); assertThat(JSON.readTree(response.body())).isEmpty(); }
        } finally { rerankHook=() -> {}; }
    }

    @Test
    void oneCandidateSkipsPaidRerank() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        int before=rerankCalls;
        var response=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\",\"rerank\":true}");
        assertThat(response.headers().firstValue("X-Rerank-Status")).contains("INSUFFICIENT_CANDIDATES");
        assertThat(response.headers().firstValue("X-Rerank-Model")).isEmpty();
        assertThat(rerankCalls).isEqualTo(before);
    }

    @Test
    void disabledRerankerReportsDisabledInsteadOfApplied() {
        long task=seed(2); processor.processNext(); indexAllBm25();
        var empty=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var service=new io.github.xw66.knowflowai.retrieval.SearchService(jdbc,
                context.getBean(io.github.xw66.knowflowai.knowledge.KnowledgeBaseService.class),
                context.getBeanProvider(org.springframework.ai.embedding.EmbeddingModel.class),context.getBeanProvider(QdrantIndex.class),
                context.getBeanProvider(io.github.xw66.knowflowai.retrieval.LuceneIndex.class),
                empty.getBeanProvider(io.github.xw66.knowflowai.retrieval.RerankClient.class),
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class),context.getBean(io.github.xw66.knowflowai.ingestion.EmbeddingCalls.class));
        int before=rerankCalls;
        var result=service.search(owner(task),base(task),"测试段落",5,io.github.xw66.knowflowai.retrieval.SearchService.Mode.HYBRID,true);
        assertThat(result.rerankStatus()).isEqualTo(io.github.xw66.knowflowai.retrieval.SearchService.RerankStatus.DISABLED);
        assertThat(result.rerankModel()).isNull();
        assertThat(result.hits()).hasSize(2);
        assertThat(rerankCalls).isEqualTo(before);
    }

    @Test
    void hybridFusesRealIndexesAndCountsOnlyReadyBm25Progress() throws Exception {
        long task=seed(1); processor.processNext();
        var before=request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}");
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(before.body()).get(0).path("score").asDouble()).isCloseTo(1.0/61,org.assertj.core.data.Offset.offset(1e-12));
        indexAllBm25();
        var response=request(base(task),owner(task),"{\"query\":\"测试段落\",\"topK\":1,\"mode\":\"HYBRID\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        var hits=JSON.readTree(response.body());
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("content").asText()).isEqualTo("测试段落0");
        assertThat(hits.get(0).path("score").asDouble()).isCloseTo(2.0/61,org.assertj.core.data.Offset.offset(1e-12));
        jdbc.sql("UPDATE bm25_index_progress p JOIN document_task t ON t.document_id=p.document_id SET p.instance_id='stale' WHERE t.id=:id")
                .param("id",task).update();
        assertThat(search.search(owner(task),base(task),"测试段落",5,io.github.xw66.knowflowai.retrieval.SearchService.Mode.HYBRID))
                .singleElement().satisfies(hit -> assertThat(hit.score()).isCloseTo(1.0/61,org.assertj.core.data.Offset.offset(1e-12)));
    }

    @Test
    void hybridRejectsForgedCrossBaseCandidatesFromBothRoutes() throws Exception {
        long task=seed(1); processor.processNext();
        long other=seed(1); processor.processNext(); indexAllBm25();
        var row=jdbc.sql("SELECT c.id,c.document_id FROM document_chunk c JOIN document_task t ON t.document_id=c.document_id WHERE t.id=:id")
                .param("id",other).query().singleRow();
        long chunk=((Number)row.get("id")).longValue(), document=((Number)row.get("document_id")).longValue();
        index.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{0.1f,1.1f,2.1f},
                "payload",Map.of("knowledge_base_id",base(task),"document_id",document,"chunk_id",chunk,"index_version",1))));
        lexicalIndex.replace(document,base(task),1,java.util.List.of(new io.github.xw66.knowflowai.retrieval.LuceneIndex.Chunk(chunk,"测试段落0")));
        var hits=search.search(owner(task),base(task),"测试段落",5,io.github.xw66.knowflowai.retrieval.SearchService.Mode.HYBRID);
        assertThat(hits).hasSize(1).allMatch(hit -> hit.documentId()!=document);
        assertThat(hits.getFirst().score()).isCloseTo(2.0/61,org.assertj.core.data.Offset.offset(1e-12));
        int before=calls;
        assertThat(request(base(task),owner(other),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}").statusCode()).isEqualTo(404);
        assertThat(calls).isEqualTo(before);
    }

    @Test
    void hybridRechecksRevocationAndDoesNotHideModelFailureWithLexicalResults() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        responseStatus=503;
        try { assertThat(request(base(task),owner(task),"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}").statusCode()).isEqualTo(503); }
        finally { responseStatus=200; }
        long user=owner(task), base=base(task);
        modelHook=() -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:base AND user_id=:user").param("base",base).param("user",user).update();
        try { assertThat(request(base,user,"{\"query\":\"测试段落\",\"mode\":\"HYBRID\"}").statusCode()).isEqualTo(404); }
        finally { modelHook=() -> {}; }
    }

    private void indexAllBm25() {
        int count=jdbc.sql("SELECT COUNT(*) FROM document").query(Integer.class).single();
        for (int i=0;i<count;i++) bm25.processNext();
    }

    @Test
    void deletionDuringEmbeddingFencesCompletionAndRechecksLateWrites() {
        long task=seed(1), user=owner(task), base=base(task);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        modelHook=() -> {
            documents.delete(user,base,document);
            cleanup.processNext();
        };
        try { processor.processNext(); } finally { modelHook=() -> {}; }
        assertThat(state(task)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT status FROM document WHERE id=:id").param("id",document).query(String.class).single()).isEqualTo("DELETED");
        assertThat(count(task)).isEqualTo(1);
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE vector_cleanup SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",document).update();
        cleanup.processNext();
        assertThat(count(task)).isZero();
        assertThat(jdbc.sql("SELECT last_cleaned_at IS NOT NULL FROM vector_cleanup WHERE document_id=:id").param("id",document).query(Boolean.class).single()).isTrue();
    }

    @Test
    void cleanupRetriesOutageAndDoesNotDeleteOtherDocuments() {
        long task=seed(1); processor.processNext();
        long other=seed(1); processor.processNext();
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        documents.delete(owner(task),base(task),document);
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        try { cleanup.processNext(); }
        finally { QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec(); }
        assertThat(jdbc.sql("SELECT error_code FROM vector_cleanup WHERE document_id=:id").param("id",document).query(String.class).single()).isEqualTo("VECTOR_CLEANUP_FAILED");
        assertThat(count(task)).isEqualTo(1);
        jdbc.sql("UPDATE vector_cleanup SET available_at=CURRENT_TIMESTAMP(6) WHERE document_id=:id").param("id",document).update();
        int before=calls;
        cleanup.processNext();
        assertThat(calls).isEqualTo(before);
        assertThat(count(task)).isZero();
        assertThat(count(other)).isEqualTo(1);
    }

    @Test
    void cleanupUsesAllRecordedCollectionsAndRecoversExpiredLease() {
        long task=seed(1); processor.processNext();
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        var previousIndex=new QdrantIndex("http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333),"",3,"previous-model","http://test.invalid/v1");
        previousIndex.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{0.1f,1.1f,2.1f},
                "payload",Map.of("document_id",document,"knowledge_base_id",base(task)))));
        jdbc.sql("UPDATE document SET vector_collection=:collection WHERE id=:id").param("collection",previousIndex.collection()).param("id",document).update();
        documents.delete(owner(task),base(task),document);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vector_cleanup WHERE document_id=:id").param("id",document).query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("UPDATE vector_cleanup SET lease_token=:token,available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE document_id=:id")
                .param("token",UUID.randomUUID().toString()).param("id",document).update();
        cleanup.processNext(); cleanup.processNext();
        assertThat(count(task)).isZero();
        assertThat(previousIndex.search(new float[]{0.1f,1.1f,2.1f},base(task),10).path("result").path("points")).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vector_cleanup WHERE document_id=:id AND last_cleaned_at IS NOT NULL AND lease_token IS NULL")
                .param("id",document).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void searchEndpointReturnsOnlyAuthorizedActiveChunksAndValidatesRequests() throws Exception {
        long task = seed(1);
        processor.processNext();
        long base = base(task), user = owner(task);
        var result = request(base, user, "{\"query\":\"测试\",\"topK\":5}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JSON.readTree(result.body()).get(0).path("content").asText()).isEqualTo("测试段落0");
        assertThat(request(base, user, "{\"query\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(request(base, user, "{\"query\":\"测试\",\"topK\":21}").statusCode()).isEqualTo(400);
        assertThat(request(base, user, "{\"query\":\"测试\",\"userId\":1}").statusCode()).isEqualTo(400);
        assertThat(request(base, 0, "{\"query\":\"测试\"}").statusCode()).isEqualTo(401);
        long other = seed(1); processor.processNext();
        int before = calls;
        assertThat(request(base, owner(other), "{\"query\":\"测试\"}").statusCode()).isEqualTo(404);
        jdbc.sql("UPDATE app_user SET system_role='ADMIN' WHERE id=:id").param("id",owner(other)).update();
        assertThat(request(base, owner(other), "{\"query\":\"测试\"}").statusCode()).isEqualTo(404);
        assertThat(calls).isEqualTo(before);
        assertThat(index.search(new float[]{0.1f,1.1f,2.1f}, base,200).path("result").path("points"))
                .allMatch(point -> point.path("payload").path("knowledge_base_id").asLong() == base);
    }

    @Test
    void rebuildingFailureKeepsOldSearchAndSuccessfulVersionSwitchesAtomically() {
        long task=seed(1); processor.processNext();
        long user=owner(task), base=base(task);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        var retry=documents.reindex(user,base,document,UUID.randomUUID().toString());
        assertThat(search.search(user,base,"测试",5)).hasSize(1);
        jdbc.sql("UPDATE document_task SET stage='QUEUED',received_at=CURRENT_TIMESTAMP(6),attempts=2 WHERE id=:id").param("id",retry.taskId()).update();
        parser.processNext();
        assertThat(state(retry.taskId())).isEqualTo("FAILED");
        assertThat(search.search(user,base,"测试",5)).hasSize(1);
        var next=documents.reindex(user,base,document,UUID.randomUUID().toString());
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,3,0,1,'新版本正文')").param("id",document).update();
        jdbc.sql("UPDATE document_task SET stage='CHUNKED',received_at=CURRENT_TIMESTAMP(6) WHERE id=:id").param("id",next.taskId()).update();
        assertThat(search.search(user,base,"测试",5)).extracting(io.github.xw66.knowflowai.retrieval.SearchService.Hit::content).containsExactly("测试段落0");
        processor.processNext();
        assertThat(state(next.taskId())).isEqualTo("SUCCEEDED");
        assertThat(active(next.taskId())).isEqualTo(3);
        assertThat(search.search(user,base,"测试",5)).extracting(io.github.xw66.knowflowai.retrieval.SearchService.Hit::content).containsExactly("新版本正文");
    }

    @Test
    void staleFailedWrongCollectionAndForgedCrossBaseCandidatesAreExcluded() {
        long task=seed(1); processor.processNext();
        long user=owner(task), base=base(task);
        for (String status : java.util.List.of("FAILED","DELETED","PROCESSING")) {
            jdbc.sql("UPDATE document SET status=:status WHERE knowledge_base_id=:base").param("status",status).param("base",base).update();
            assertThat(search.search(user,base,"测试",5)).isEmpty();
        }
        jdbc.sql("UPDATE document SET status='READY', active_index_version=2 WHERE knowledge_base_id=:base").param("base",base).update();
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE document SET active_index_version=1, vector_collection='wrong' WHERE knowledge_base_id=:base").param("base",base).update();
        assertThat(search.search(user,base,"测试",5)).isEmpty();
        jdbc.sql("UPDATE document SET vector_collection=:collection WHERE knowledge_base_id=:base").param("collection",index.collection()).param("base",base).update();
        long other=seed(1); processor.processNext();
        var row=jdbc.sql("SELECT c.id,c.document_id FROM document_chunk c JOIN document_task t ON t.document_id=c.document_id WHERE t.id=:id").param("id",other).query().singleRow();
        index.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{0.1f,1.1f,2.1f},
                "payload",Map.of("knowledge_base_id",base,"document_id",row.get("document_id"),"chunk_id",row.get("id"),"index_version",1))));
        assertThat(search.search(user,base,"测试",5)).hasSize(1).allMatch(hit -> hit.documentId()!=((Number)row.get("document_id")).longValue());
    }

    @Test
    void revocationDuringEmbeddingIsRecheckedAndModelErrorsAreSanitized() throws Exception {
        long task=seed(1); processor.processNext();
        long base=base(task), user=owner(task);
        modelHook=() -> jdbc.sql("DELETE FROM knowledge_member WHERE knowledge_base_id=:base AND user_id=:user").param("base",base).param("user",user).update();
        try { assertThat(request(base,user,"{\"query\":\"测试\"}").statusCode()).isEqualTo(404); }
        finally { modelHook=() -> {}; }
        jdbc.sql("INSERT INTO knowledge_member(knowledge_base_id,user_id,role) VALUES (:base,:user,'VIEWER')").param("base",base).param("user",user).update();
        responseStatus=503;
        try {
            var response=request(base,user,"{\"query\":\"测试\"}");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("test-only","test failure","测试段落");
        } finally { responseStatus=200; }
        assertThat(request(base,user,"{\"query\":\"测试\"}").statusCode()).isEqualTo(200);
    }

    private long base(long task) { return jdbc.sql("SELECT d.knowledge_base_id FROM document d JOIN document_task t ON t.document_id=d.id WHERE t.id=:id").param("id",task).query(Long.class).single(); }
    private long owner(long task) { return jdbc.sql("SELECT owner_id FROM knowledge_base WHERE id=:id").param("id",base(task)).query(Long.class).single(); }
    private java.net.http.HttpResponse<String> request(long base,long user,String body) throws Exception {
        return request(base,user,body,"search");
    }
    private java.net.http.HttpResponse<String> request(long base,long user,String body,String endpoint) throws Exception {
        try(var client=java.net.http.HttpClient.newHttpClient()) { return client.send(buildRequest(base,user,body,endpoint),java.net.http.HttpResponse.BodyHandlers.ofString()); }
    }
    private java.net.http.HttpRequest buildRequest(long base,long user,String body,String endpoint) {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/knowledge-bases/"+base+"/"+endpoint))
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (user>0) {
            var now=java.time.Instant.now();
            var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("knowflow-ai").audience(java.util.List.of("knowflow-api"))
                    .subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
            String token=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
            request.header("Authorization","Bearer "+token);
        }
        return request.build();
    }

    @Test
    void batchesActivateOnlyWhenCompleteAndReplayDoesNotDuplicatePoints() {
        long task = seed(17);
        assertThat(documents.task(owner(task), task).stage()).isEqualTo("CHUNKED");
        modelHook = () -> assertThat(documents.task(owner(task), task).status()).isEqualTo("PROCESSING");
        try { processor.processNext(); } finally { modelHook = () -> {}; }
        assertThat(redis.hasKey("knowflow:task:v1:" + task + ":1")).isFalse();
        assertThat(redis.hasKey("knowflow:task:v1:" + task + ":2")).isFalse();
        assertThat(documents.task(owner(task), task).stage()).isEqualTo("CHUNKED");
        assertThat(state(task)).isEqualTo("PENDING");
        assertThat(active(task)).isNull();
        assertThat(count(task)).isEqualTo(10);
        processor.processNext();
        assertThat(redis.hasKey("knowflow:task:v1:" + task + ":3")).isFalse();
        assertThat(jdbc.sql("SELECT cache_version FROM document_task WHERE id=:id").param("id", task).query(Long.class).single()).isEqualTo(5);
        assertThat(documents.task(owner(task), task).status()).isEqualTo("SUCCEEDED");
        assertThat(state(task)).isEqualTo("SUCCEEDED");
        assertThat(active(task)).isEqualTo(1);
        assertThat(count(task)).isEqualTo(17);
        assertThat(requestedPath).isEqualTo("/v1/embeddings");
        assertThat(jdbc.sql("SELECT total_tokens FROM model_call WHERE task_id=:id AND call_type='EMBEDDING' AND route='INDEX' ORDER BY id").param("id",task).query(Integer.class).list()).containsExactly(10,7);
        jdbc.sql("UPDATE document_task SET status = 'PROCESSING', stage = 'INDEXING', vector_cursor = -1, lease_token = 'crashed', lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id = :id")
                .param("id", task).update();
        processor.processNext(); processor.processNext();
        assertThat(count(task)).isEqualTo(17);
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void modelFailureAndWrongDimensionsNeverActivateAndRecoveryResumes() {
        long task = seed(1);
        responseStatus = 503;
        int before = calls;
        try { processor.processNext(); } finally { responseStatus = 200; }
        assertThat(calls - before).isEqualTo(1);
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        due(task);
        responseDimensions = 2;
        try { processor.processNext(); } finally { responseDimensions = 3; }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT status FROM model_call WHERE task_id=:id ORDER BY id").param("id",task).query(String.class).list()).containsExactly("FAILED","COMPLETED","COMPLETED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE task_id=:id AND status='FAILED' AND total_tokens IS NULL AND NOT usage_known").param("id",task).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void repeatedModelFailureStopsAtBudget() {
        long task = seed(1);
        responseStatus = 429;
        try {
            for (int i = 0; i < 3; i++) {
                due(task);
                long version = jdbc.sql("SELECT cache_version FROM document_task WHERE id=:id").param("id", task).query(Long.class).single();
                documents.task(owner(task), task);
                processor.processNext();
                assertThat(redis.hasKey("knowflow:task:v1:" + task + ":" + version)).isFalse();
            }
        } finally { responseStatus = 200; }
        assertThat(state(task)).isEqualTo("FAILED");
        assertThat(active(task)).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE task_id=:id AND status='FAILED'").param("id",task).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void queryEmbeddingHasSeparateUsageAndLedgerOutagePreventsBothPaths() {
        long task=seed(1); processor.processNext();
        long last=jdbc.sql("SELECT COALESCE(MAX(id),0) FROM model_call").query(Long.class).single();
        search.search(owner(task),base(task),"测试段落",5);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE id>:last AND call_type='EMBEDDING' AND route='QUERY' AND task_id IS NULL AND status='COMPLETED' AND input_tokens=1 AND output_tokens=0 AND total_tokens=1 AND usage_known").param("last",last).query(Long.class).single()).isEqualTo(1);
        long pending=seed(1);
        int before=calls;
        jdbc.sql("RENAME TABLE model_call TO model_call_unavailable").update();
        try {
            processor.processNext();
            assertThat(state(pending)).isEqualTo("RETRY_WAIT");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->search.search(owner(task),base(task),"测试段落",5)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(calls).isEqualTo(before);
        } finally { jdbc.sql("RENAME TABLE model_call_unavailable TO model_call").update(); }
        due(pending); processor.processNext();
        assertThat(state(pending)).isEqualTo("SUCCEEDED");
        assertThat(calls).isEqualTo(before+1);
    }

    @Test
    void exhaustedVectorLeaseInvalidatesCachedProcessingState() {
        long task = seed(1);
        jdbc.sql("UPDATE document_task SET status='PROCESSING',stage='INDEXING',vector_attempts=max_attempts,lease_token='crashed',lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=:id")
                .param("id", task).update();
        assertThat(documents.task(owner(task), task).status()).isEqualTo("PROCESSING");
        processor.processNext();
        assertThat(redis.hasKey("knowflow:task:v1:" + task + ":1")).isFalse();
        assertThat(documents.task(owner(task), task).errorCode()).isEqualTo("VECTOR_RETRY_EXHAUSTED");
    }

    @Test
    void qdrantFailureDoesNotAdvanceCursorAndRecovers() {
        long task = seed(1);
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        try { processor.processNext(); }
        finally { QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec(); }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        assertThat(jdbc.sql("SELECT vector_cursor FROM document_task WHERE id=:id").param("id", task).query(Integer.class).single()).isEqualTo(-1);
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void mysqlCommitFailureAfterQdrantWriteCanReplayWithoutDuplicate() {
        long task = seed(1);
        long documentId = jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id", task).query(Long.class).single();
        jdbc.sql("ALTER TABLE document_task ADD CONSTRAINT reject_vector_completion CHECK (document_id <> "
                + documentId + " OR status <> 'SUCCEEDED')").update();
        try { processor.processNext(); }
        finally { jdbc.sql("ALTER TABLE document_task DROP CHECK reject_vector_completion").update(); }
        assertThat(state(task)).isEqualTo("RETRY_WAIT");
        assertThat(active(task)).isNull();
        assertThat(count(task)).isEqualTo(1);
        due(task); processor.processNext();
        assertThat(state(task)).isEqualTo("SUCCEEDED");
        assertThat(count(task)).isEqualTo(1);
    }

    @Test
    void externalReconciliationChecksIdentitiesFilesAndCommittedLuceneWithoutMutation() throws Exception {
        long task=seed(1); processor.processNext(); indexAllBm25();
        long doc=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        String key=jdbc.sql("SELECT storage_key FROM document WHERE id=:id").param("id",doc).query(String.class).single();
        byte[] bytes="测试文件".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(documentDirectory.resolve(key),bytes);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.sql("UPDATE document SET sha256=:hash WHERE id=:id").param("hash",hash).param("id",doc).update();
        var normal=reconciliation.report(base(task),0,5).getBody().documents().getFirst();
        assertThat(normal.file().status()).isEqualTo("OK");
        assertThat(normal.qdrant().status()).isEqualTo("OK");
        assertThat(normal.lucene().status()).isEqualTo("OK");
        assertThat(normal.consistency()).isEqualTo("OBSERVED");
        index.upsert(java.util.List.of(Map.of("id",UUID.nameUUIDFromBytes((doc+":1:0").getBytes(StandardCharsets.UTF_8)).toString(),
                "vector",new float[]{1,0,0},"payload",Map.of("document_id",doc,"knowledge_base_id",base(task),"index_version",1,"chunk_id",999999))));
        assertThat(reconciliation.report(base(task),0,5).getBody().documents().getFirst().qdrant().status()).isEqualTo("MISMATCH");
        lexicalIndex.replace(doc,base(task),1,java.util.List.of());
        java.nio.file.Files.writeString(documentDirectory.resolve(key),"变更内容");
        var changed=reconciliation.report(base(task),0,5).getBody().documents().getFirst();
        assertThat(changed.file().status()).isEqualTo("MISMATCH");
        assertThat(changed.lucene().status()).isEqualTo("MISSING");
        assertThat(changed.lucene().missingChunkIds()).hasSize(1);
        index.deleteDocument(index.collection(),doc);
        java.nio.file.Files.delete(documentDirectory.resolve(key));
        var missing=reconciliation.report(base(task),0,5).getBody().documents().getFirst();
        assertThat(missing.file().status()).isEqualTo("MISSING");
        assertThat(missing.qdrant().status()).isEqualTo("MISSING");
        assertThat(active(task)).isEqualTo(1);
        assertThat(state(task)).isEqualTo("SUCCEEDED");
    }

    @Test
    void externalReconciliationDoesNotCallPausedQdrantMissing() {
        long task=seed(1); processor.processNext();
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        try {
            assertThat(reconciliation.report(base(task),0,5).getBody().documents().getFirst().qdrant().status()).isEqualTo("UNAVAILABLE");
        } finally { QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec(); }
        assertThat(reconciliation.report(base(task),0,5).getBody().documents().getFirst().qdrant().status()).isEqualTo("OK");
        assertThat(active(task)).isEqualTo(1);
    }

    @Test
    void vectorOrphanScanClassifiesMissingDocumentsAndCurrentPoints() {
        long task=seed(1); processor.processNext();
        long doc=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        index.upsert(java.util.List.of(Map.of("id",UUID.randomUUID().toString(),"vector",new float[]{1,0,0},
                "payload",Map.of("document_id",999999999L,"knowledge_base_id",999999999L,"index_version",1,"chunk_id",999999999L))));
        var page=reconciliation.vectors(index.collection(),"",100).getBody();
        assertThat(page.scanStatus()).isEqualTo("OK");
        assertThat(page.points()).anyMatch(point -> point.status().equals("ORPHAN") && point.documentId()==999999999L);
        assertThat(page.points()).anyMatch(point -> point.status().equals("REGISTERED_ACTIVE") && point.documentId()==doc);
    }

    @Test
    void collectionScanReportsQdrantCollectionWithoutDatabaseRegistration() {
        String extra="knowflow_0123456789abcdef0123456789abcdef";
        var client=RestClient.create("http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333));
        client.put().uri("/collections/"+extra).body(Map.of("vectors",Map.of("size",3,"distance","Cosine"))).retrieve().toBodilessEntity();
        try {
            long task=seed(1); processor.processNext();
            long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
            jdbc.sql("UPDATE document SET status='READY',active_index_version=1,vector_collection=:collection WHERE id=:id")
                    .param("collection",index.collection()).param("id",document).update();
            var page=reconciliation.collections().getBody();
            assertThat(page.scanStatus()).isEqualTo("OK");
            assertThat(page.collections()).anyMatch(item -> item.name().equals(extra) && item.status().equals("ORPHAN_COLLECTION"));
            assertThat(page.collections()).anyMatch(item -> item.name().equals(index.collection()) && item.status().equals("REGISTERED"));
        } finally { client.delete().uri("/collections/"+extra).retrieve().toBodilessEntity(); }
    }

    @Test
    void externalReconciliationMarksConcurrentVersionChangeAndRetainsDeletedIndexes() {
        long task=seed(1); processor.processNext(); indexAllBm25();
        long doc=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        var malformed=new java.util.concurrent.atomic.AtomicBoolean();
        var hooked=new QdrantIndex("http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333),"",3,"test","http://unused") {
            @Override public JsonNode inspectDocument(String collection,long id,int limit) {
                if(malformed.get()) return JSON.readTree("{\"status\":\"ok\",\"result\":{\"points\":[{}]}}");
                var result=super.inspectDocument(collection,id,limit);
                jdbc.sql("UPDATE document SET index_version=index_version+1 WHERE id=:id").param("id",id).update();
                return result;
            }
        };
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("vector",hooked,"bm25",lexicalIndex));
        var scoped=new io.github.xw66.knowflowai.observability.ExternalReconcileController(jdbc,
                context.getBean(io.github.xw66.knowflowai.document.DocumentStorage.class),beans.getBeanProvider(QdrantIndex.class),beans.getBeanProvider(io.github.xw66.knowflowai.retrieval.LuceneIndex.class));
        assertThat(scoped.report(base(task),0,5).getBody().documents().getFirst().consistency()).isEqualTo("CHANGED");
        malformed.set(true);
        assertThat(scoped.report(base(task),0,5).getBody().documents().getFirst().qdrant().status()).isEqualTo("UNAVAILABLE");
        jdbc.sql("UPDATE document SET status='DELETED' WHERE id=:id").param("id",doc).update();
        var deleted=reconciliation.report(base(task),0,5).getBody().documents().getFirst();
        assertThat(deleted.qdrant().status()).isEqualTo("PENDING_CLEANUP");
        assertThat(deleted.lucene().status()).isEqualTo("PENDING_CLEANUP");
        assertThat(count(task)).isEqualTo(1);
    }

    @Test
    void externalReconciliationDoesNotClaimCompleteAboveChunkLimit() {
        long task=seed(1);
        long doc=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        String rows=java.util.stream.IntStream.rangeClosed(1,2000).mapToObj(i->"(:doc,1,"+i+","+(i+1)+",'测试')").collect(java.util.stream.Collectors.joining(","));
        jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES "+rows).param("doc",doc).update();
        jdbc.sql("UPDATE document SET status='READY',active_index_version=1,vector_collection=:collection WHERE id=:id")
                .param("collection",index.collection()).param("id",doc).update();
        jdbc.sql("UPDATE document_task SET status='SUCCEEDED' WHERE id=:id").param("id",task).update();
        var report=reconciliation.report(base(task),0,1).getBody().documents().getFirst();
        assertThat(report.database()).isEqualTo("PARTIAL");
        assertThat(report.qdrant().status()).isEqualTo("PARTIAL");
        assertThat(report.lucene().status()).isEqualTo("PARTIAL");
    }

    private void due(long id) { jdbc.sql("UPDATE document_task SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=:id").param("id", id).update(); }
    private String state(long id) { return jdbc.sql("SELECT status FROM document_task WHERE id=:id").param("id", id).query(String.class).single(); }
    private Integer active(long id) { return (Integer) jdbc.sql("SELECT d.active_index_version AS version FROM document d JOIN document_task t ON d.id=t.document_id WHERE t.id=:id").param("id",id).query().singleRow().get("version"); }
    private long count(long id) {
        long documentId = jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",id).query(Long.class).single();
        return RestClient.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333))
                .post().uri("/collections/" + index.collection() + "/points/count")
                .body(Map.of("exact", true, "filter", Map.of("must", java.util.List.of(Map.of("key", "document_id", "match", Map.of("value", documentId))))))
                .retrieve().body(JsonNode.class).path("result").path("count").asLong();
    }
    private long seed(int count) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO app_user(username,password_hash) VALUES (:name,'unused')").param("name",unique).update();
        long user = jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO knowledge_base(name,owner_id) VALUES (:name,:owner)").param("name",unique).param("owner",user).update();
        long kb = jdbc.sql("SELECT id FROM knowledge_base WHERE name=:name").param("name",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO knowledge_member(knowledge_base_id,user_id,role) VALUES (:base,:user,'OWNER')").param("base",kb).param("user",user).update();
        jdbc.sql("INSERT INTO document(knowledge_base_id,uploaded_by,name,storage_key,sha256,media_type,size_bytes,idempotency_key,status) VALUES (:kb,:user,'test.txt',:key,:hash,'text/plain',1,:key,'PROCESSING')")
                .param("kb",kb).param("user",user).param("key",unique).param("hash","0".repeat(64)).update();
        long document = jdbc.sql("SELECT id FROM document WHERE storage_key=:key").param("key",unique).query(Long.class).single();
        jdbc.sql("INSERT INTO document_task(document_id,stage,received_at) VALUES (:id,'CHUNKED',CURRENT_TIMESTAMP(6))").param("id",document).update();
        for (int i=0;i<count;i++) jdbc.sql("INSERT INTO document_chunk(document_id,index_version,chunk_index,paragraph_number,content) VALUES (:id,1,:position,:paragraph,:content)")
                .param("id",document).param("position",i).param("paragraph",i+1).param("content","测试段落"+i).update();
        return jdbc.sql("SELECT id FROM document_task WHERE document_id=:id").param("id",document).query(Long.class).single();
    }
}
