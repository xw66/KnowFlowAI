package io.github.xw66.knowflowai.chat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ChatCallsTests {
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
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    ChatModel primary,backup;
    final AtomicInteger primaryCalls=new AtomicInteger(),backupCalls=new AtomicInteger(),closed=new AtomicInteger();
    volatile int primaryStatus=200,backupStatus=200;
    volatile String primaryMode="OK",backupMode="OK";
    volatile JsonNode backupRequest;
    volatile boolean streamUsage;
    @BeforeEach void start() throws Exception {
        jdbc.sql("DELETE FROM model_call").update();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange-> {
            boolean fallback=exchange.getRequestURI().getPath().startsWith("/backup");
            (fallback?backupCalls:primaryCalls).incrementAndGet();
            var request=json.readTree(exchange.getRequestBody().readAllBytes());
            if(fallback) backupRequest=request;
            int status=fallback?backupStatus:primaryStatus;
            String mode=fallback?backupMode:primaryMode,model=fallback?"backup-model":"primary-model";
            boolean stream=request.path("stream").asBoolean();
            exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(status,0);
            try {
                var output=exchange.getResponseBody();
                if(status!=200) output.write(json.writeValueAsBytes(Map.of("error",Map.of("message","test failure","type","test_error"))));
                else if(stream) {
                    if(!mode.equals("STALL_FIRST")) { output.write(chunk(model,"正文",false).getBytes(StandardCharsets.UTF_8)); output.flush(); }
                    if(mode.startsWith("STALL")) {
                        while(true) { output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); output.flush(); pause(); }
                    }
                    output.write(chunk(model,"",true).getBytes(StandardCharsets.UTF_8));
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } else if(mode.equals("STALL_FIRST")) {
                    while(true) { output.write(' '); output.flush(); pause(); }
                } else output.write(json.writeValueAsBytes(Map.of("id","test","object","chat.completion","created",1,"model",model,
                        "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","answer"),"finish_reason","stop")),
                        "usage",Map.of("prompt_tokens",5,"completion_tokens",2,"total_tokens",7))));
                output.flush();
            } catch(java.io.IOException error) { closed.incrementAndGet(); }
            finally { exchange.close(); }
        });
        server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        primary=ChatConfiguration.create("test-only",url+"/primary","primary-model",512,Duration.ofSeconds(10),Duration.ofSeconds(1),Duration.ofSeconds(10));
        backup=ChatConfiguration.create("test-only",url+"/backup","backup-model",512,Duration.ofSeconds(10),Duration.ofSeconds(1),Duration.ofSeconds(10));
    }
    @AfterEach void stop() {
        server.stop(0);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='RUNNING'").query(Long.class).single()==0);
    }
    private static void pause() { try { Thread.sleep(25); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } }
    private String chunk(String model,String text,boolean stop) {
        var choice=new java.util.LinkedHashMap<String,Object>();
        choice.put("index",0); choice.put("delta",Map.of("content",text)); choice.put("finish_reason",stop?"stop":null);
        var frame=new java.util.LinkedHashMap<String,Object>(Map.of("id","test","object","chat.completion.chunk","created",1,"model",model,"choices",List.of(choice)));
        if(streamUsage) frame.put("usage",Map.of("prompt_tokens",5,"completion_tokens",2,"total_tokens",7));
        return "data: "+json.writeValueAsString(frame)+"\n\n";
    }
    private ChatCalls calls(boolean fallback,int retries,Duration total) {
        var factory=new StaticListableBeanFactory();
        if(fallback) factory.addBean("backup",backup);
        return new ChatCalls(factory.getBeanProvider(ChatModel.class),retries,Duration.ofMillis(500),Duration.ofMillis(300),total,log);
    }
    @Test void transientFailureUsesBoundedRetriesThenBackupWithItsOwnOptions() {
        primaryStatus=503;
        var result=calls(true,1,Duration.ofSeconds(5)).call(primary,new Prompt("test"));
        assertThat(primaryCalls.get()).isEqualTo(2);
        assertThat(backupCalls.get()).isEqualTo(1);
        assertThat(result.getMetadata().getModel()).isEqualTo("backup-model");
        assertThat(backupRequest.path("model").asText()).isEqualTo("backup-model");
        assertThat(backupRequest.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(backupRequest.path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(backupRequest.at("/response_format/type").asText()).isEqualTo("json_object");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()-> {
            assertThat(jdbc.sql("SELECT status FROM model_call ORDER BY id").query(String.class).list()).containsExactly("FAILED","FAILED","COMPLETED");
            assertThat(jdbc.sql("SELECT COUNT(DISTINCT invocation_id) FROM model_call").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT total_tokens FROM model_call WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(7);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='FAILED' AND total_tokens IS NULL AND NOT usage_known").query(Long.class).single()).isEqualTo(2);
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403})
    void permanentFailuresNeverRetryOrFallback(int status) {
        primaryStatus=status;
        assertThatThrownBy(()->calls(true,2,Duration.ofSeconds(5)).call(primary,new Prompt("test"))).isInstanceOf(com.openai.errors.OpenAIServiceException.class);
        assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isZero();
    }
    @Test void streamFallsBackBeforeAnyBodyAndReportsActualModel() {
        primaryStatus=429;
        var responses=calls(true,0,Duration.ofSeconds(5)).stream(primary,new Prompt("test")).collectList().block(Duration.ofSeconds(6));
        assertThat(responses).isNotEmpty().allMatch(response->response.getMetadata().getModel().equals("backup-model"));
        assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isEqualTo(1);
        assertThat(backupRequest.at("/response_format/type").asText()).isEqualTo("text");
        assertThat(backupRequest.path("max_tokens").asInt()).isEqualTo(512);
    }
    @Test void firstTokenTimeoutCanFallbackAndCancelsOriginalConnection() {
        primaryMode="STALL_FIRST";
        var responses=calls(true,0,Duration.ofSeconds(5)).stream(primary,new Prompt("test")).collectList().block(Duration.ofSeconds(6));
        assertThat(responses).isNotEmpty().allMatch(response->response.getMetadata().getModel().equals("backup-model"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->closed.get()>0);
    }
    @Test void idleTimeoutAfterBodyNeverRetriesOrSwitches() {
        primaryMode="STALL_AFTER";
        var received=new java.util.concurrent.CopyOnWriteArrayList<String>();
        assertThatThrownBy(()->calls(true,2,Duration.ofSeconds(5)).stream(primary,new Prompt("test"))
                .doOnNext(response->received.add(response.getResult().getOutput().getText())).collectList().block(Duration.ofSeconds(6)))
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(received).contains("正文");
        assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isZero();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->closed.get()>0);
    }
    @Test void totalDeadlineBoundsSlowSynchronousResponseAndClosesConnection() {
        primaryMode="STALL_FIRST";
        assertThatThrownBy(()->calls(false,0,Duration.ofMillis(700)).call(primary,new Prompt("test"))).isInstanceOf(RuntimeException.class);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->closed.get()>0);
        assertThat(primaryCalls.get()).isEqualTo(1);
    }
    @Test void cancellingStreamPreventsRetriesAndClosesTransport() {
        primaryMode="STALL_AFTER";
        var first=calls(true,2,Duration.ofSeconds(5)).stream(primary,new Prompt("test")).take(1).collectList().block(Duration.ofSeconds(3));
        assertThat(first).hasSize(1);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->closed.get()>0);
        assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isZero();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("CANCELLED"));
    }
    @Test void cumulativeStreamUsageIsNotAddedAcrossFrames() {
        streamUsage=true;
        usageCalls().stream(primary,new Prompt("test")).collectList().block(Duration.ofSeconds(6));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()-> {
            assertThat(jdbc.sql("SELECT status FROM model_call").query(String.class).single()).isEqualTo("COMPLETED");
            assertThat(jdbc.sql("SELECT total_tokens FROM model_call").query(Integer.class).single()).isEqualTo(7);
        });
    }
    @Test void missingStreamUsageRemainsUnknown() {
        usageCalls().stream(primary,new Prompt("test")).collectList().block(Duration.ofSeconds(6));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE status='COMPLETED' AND NOT usage_known AND total_tokens IS NULL").query(Long.class).single()).isEqualTo(1));
    }
    private ChatCalls usageCalls() {
        return new ChatCalls(new StaticListableBeanFactory().getBeanProvider(ChatModel.class),0,Duration.ofSeconds(3),Duration.ofSeconds(3),Duration.ofSeconds(5),log);
    }
    @Test void unavailableLedgerPreventsProviderCalls() {
        jdbc.sql("RENAME TABLE model_call TO model_call_unavailable").update();
        try {
            assertThatThrownBy(()->calls(true,2,Duration.ofSeconds(5)).call(primary,new Prompt("test"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(primaryCalls.get()).isZero(); assertThat(backupCalls.get()).isZero();
        } finally { jdbc.sql("RENAME TABLE model_call_unavailable TO model_call").update(); }
    }
    @Test void configuredBackupDoesNotReplacePrimaryBeanAndIsUsedOnFailure() {
        primaryStatus=503;
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context->context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ChatConfiguration.class,ChatCalls.class)
                .withBean(io.github.xw66.knowflowai.observability.ModelCallLog.class,()->log)
                .withPropertyValues("spring.profiles.active=api","app.chat.enabled=true","app.chat.api-key=test-only",
                        "app.chat.base-url="+url+"/primary","app.chat.model=primary-model","app.chat.max-tokens=512","app.chat.timeout=PT5S",
                        "app.chat.fallback.enabled=true","app.chat.fallback.model=backup-model","app.chat.fallback.base-url="+url+"/backup")
                .run(context-> {
                    assertThat(context).hasNotFailed();
                    var model=context.getBean(ChatModel.class);
                    assertThat(model).isSameAs(context.getBean("chatModel"));
                    var response=context.getBean(ChatCalls.class).call(model,new Prompt("test"));
                    assertThat(response.getMetadata().getModel()).isEqualTo("backup-model");
                    assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isEqualTo(1);
                });
    }
    @Test void exhaustedBudgetStopsAfterPrimaryRetriesAndOneBackupAttempt() {
        primaryStatus=503; backupStatus=503;
        assertThatThrownBy(()->calls(true,2,Duration.ofSeconds(5)).call(primary,new Prompt("test")))
                .isInstanceOf(com.openai.errors.OpenAIServiceException.class);
        assertThat(primaryCalls.get()).isEqualTo(3); assertThat(backupCalls.get()).isEqualTo(1);
    }
    @Test void streamTotalDeadlineIsSharedAcrossModels() {
        primaryMode="STALL_FIRST"; backupMode="STALL_FIRST";
        assertThatThrownBy(()->calls(true,0,Duration.ofMillis(800)).stream(primary,new Prompt("test"))
                .collectList().block(Duration.ofSeconds(3))).isInstanceOf(RuntimeException.class);
        assertThat(primaryCalls.get()).isEqualTo(1); assertThat(backupCalls.get()).isEqualTo(1);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->closed.get()==2);
    }
}
