package io.github.xw66.knowflowai.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.xw66.knowflowai.observability.ModelCallLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

// 仅显式指定本测试及 knowflow.live=true 时运行；不属于普通回归。
@EnabledIfSystemProperty(named="knowflow.live", matches="true")
class LiveFallbackIT {
    @Test void realBackupBeforeOutputAndNoBackupAfterOutput() throws Exception {
        var env = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"))) { env.load(reader); }
        var sources = new org.springframework.core.env.MutablePropertySources();
        sources.addLast(new org.springframework.core.env.PropertiesPropertySource("local", env));
        var resolved = new org.springframework.core.env.PropertySourcesPropertyResolver(sources);
        String endpoint = env.getProperty("CHAT_BASE_URL");
        assertThat(endpoint).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        var source = new DriverManagerDataSource(env.getProperty("DB_URL", "jdbc:mysql://localhost:"+env.getProperty("MYSQL_PORT","3307")+"/knowflow?connectionTimeZone=UTC"), env.getProperty("DB_USERNAME","knowflow"), env.getProperty("DB_PASSWORD"));
        var jdbc = JdbcClient.create(source);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_price WHERE model='qwen-turbo' AND input_per_million=0.3 AND output_per_million=0.6").query(Integer.class).single()).isPositive();
        var primaryCount = new AtomicInteger();
        var fallbackCount = new AtomicInteger();
        var callId = new java.util.concurrent.atomic.AtomicLong();
        var recorded = new java.util.concurrent.CountDownLatch(1);
        // 注入故障没有外部主模型调用，不在真实账本伪造用量；真实备用调用仍使用同一数据库的预算保护。
        var ledger = new ModelCallLog(jdbc,new DataSourceTransactionManager(source),true) {
            @Override public long start(String invocation,int attempt,String type,String route,boolean streaming,Long message,String model,Long task,String url) {
                if (route.equals("PRIMARY")) { primaryCount.incrementAndGet(); return -1; }
                fallbackCount.incrementAndGet(); long id=super.start(invocation,attempt,type,route,streaming,message,model,task,url); callId.set(id); return id;
            }
            @Override public void finish(long id,String status,String model,Tokens usage,long latency,String error) {
                if (id>0) { super.finish(id,status,model,usage,latency,error); recorded.countDown(); }
            }
        };
        ChatModel backup = ChatConfiguration.create(resolved.getRequiredProperty("CHAT_API_KEY"),endpoint,"qwen-turbo",128,Duration.ofSeconds(20),Duration.ofSeconds(5),Duration.ofSeconds(30));
        var factory = new StaticListableBeanFactory(); factory.addBean("backup",backup);
        var calls = new ChatCalls(factory.getBeanProvider(ChatModel.class),0,Duration.ofSeconds(8),Duration.ofSeconds(8),Duration.ofSeconds(30),ledger);
        var partial = new ChatResponse(List.of(new Generation(new AssistantMessage("已输出的部分正文"))));
        class FaultModel implements ChatModel {
            final boolean afterOutput;
            FaultModel(boolean afterOutput) { this.afterOutput=afterOutput; }
            @Override public ChatOptions getDefaultOptions() { return ((org.springframework.ai.openai.OpenAiChatOptions)backup.getDefaultOptions()).mutate().model("qwen3.8-flash").build(); }
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return afterOutput ? Flux.concat(Flux.just(partial),Flux.error(new TimeoutException("注入正文后故障"))) : Flux.error(new TimeoutException("注入首正文前故障"));
            }
        }
        var result=calls.stream(new FaultModel(false),new Prompt("原文[C1]：差旅报销审批应在三个工作日内完成。请仅依据原文简短回答差旅报销审批多久完成，并在答案中引用[C1]。")).collectList().block(Duration.ofSeconds(35));
        assertThat(result).isNotEmpty();
        String answer=result.stream().map(r->r.getResult()==null?"":r.getResult().getOutput().getText()).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining());
        assertThat(answer).contains("三个工作日", "[C1]");
        assertThat(result).anyMatch(r->"qwen-turbo".equals(r.getMetadata().getModel()));
        assertThat(fallbackCount.get()).isEqualTo(1);
        assertThat(recorded.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var audit=jdbc.sql("SELECT id,status,actual_model,usage_known,input_tokens,output_tokens,total_tokens,estimated_cost,budget_settled FROM model_call WHERE id=:id")
                .param("id",callId.get()).query(Audit.class).single();
        assertThat(audit.status()).isEqualTo("COMPLETED");
        assertThat(audit.usageKnown()).isTrue();
        assertThat(audit.budgetSettled()).isTrue();
        var observed = new java.util.ArrayList<String>();
        assertThatThrownBy(()->calls.stream(new FaultModel(true),new Prompt("不应调用备用模型"))
                .doOnNext(r->observed.add(r.getResult().getOutput().getText())).blockLast()).hasCauseInstanceOf(TimeoutException.class);
        assertThat(observed).containsExactly("已输出的部分正文");
        assertThat(fallbackCount.get()).isEqualTo(1);
        var report=Map.of("primaryFailure","INJECTED_TIMEOUT", "backup","qwen-turbo", "actualAnswer",answer,
                "primaryAttempts",primaryCount.get(),"realBackupCalls",fallbackCount.get(),"partialOutputPreserved",observed,"ledger",audit,
                "limitation","验证人工注入主模型故障与真实备用调用的组合，不代表百炼发生过实际故障或性能评测。");
        Files.writeString(Path.of("target/live-fallback.json"),new tools.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    private record Audit(long id,String status,String actualModel,boolean usageKnown,Integer inputTokens,Integer outputTokens,Integer totalTokens,java.math.BigDecimal estimatedCost,boolean budgetSettled) {}
}
