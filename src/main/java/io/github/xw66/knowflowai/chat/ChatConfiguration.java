package io.github.xw66.knowflowai.chat;

import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods=false)
@Profile("api")
@ConditionalOnProperty(name="app.chat.enabled",havingValue="true")
public class ChatConfiguration {
    @Bean
    @org.springframework.context.annotation.Primary
    ChatModel chatModel(@Value("${app.chat.api-key}") String key, @Value("${app.chat.base-url}") String baseUrl,
            @Value("${app.chat.model}") String model, @Value("${app.chat.max-tokens}") int maxTokens,
            @Value("${app.chat.timeout}") Duration timeout,@Value("${app.chat.connect-timeout:PT5S}") Duration connect,
            @Value("${app.chat.total-timeout:PT30S}") Duration total) {
        return create(key,baseUrl,model,maxTokens,timeout,connect,total);
    }
    @Bean
    @ConditionalOnProperty(name="app.chat.fallback.enabled",havingValue="true")
    ChatModel fallbackChatModel(@Value("${app.chat.fallback.api-key:${app.chat.api-key}}") String key,
            @Value("${app.chat.fallback.base-url:${app.chat.base-url}}") String baseUrl,@Value("${app.chat.fallback.model:}") String model,
            @Value("${app.chat.max-tokens}") int maxTokens,@Value("${app.chat.timeout}") Duration timeout,
            @Value("${app.chat.connect-timeout:PT5S}") Duration connect,@Value("${app.chat.total-timeout:PT30S}") Duration total) {
        return create(key,baseUrl,model,maxTokens,timeout,connect,total);
    }
    static ChatModel create(String key,String baseUrl,String model,int maxTokens,Duration timeout,Duration connect,Duration total) {
        if (key.isBlank() || model.isBlank() || maxTokens<64 || maxTokens>2048 || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60))>0) throw new IllegalArgumentException("聊天模型配置无效");
        var format=OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build();
        if(connect.toMillis()<1 || connect.compareTo(Duration.ofMinutes(1))>0 || total.isNegative() || total.isZero() || total.compareTo(Duration.ofMinutes(2))>0)
            throw new IllegalArgumentException("连接或总期限无效");
        return OpenAiChatModel.builder().httpClientBuilderCustomizer(builder->builder
                .timeout(com.openai.core.Timeout.builder().connect(connect).read(timeout).write(timeout).request(total).build())
                .interceptor(chain->chain.withConnectTimeout((int)Math.min(chain.connectTimeoutMillis(),connect.toMillis()),java.util.concurrent.TimeUnit.MILLISECONDS)
                        .proceed(chain.request()))).options(OpenAiChatOptions.builder().apiKey(key).baseUrl(baseUrl).model(model)
                .maxTokens(maxTokens).timeout(timeout).maxRetries(0).temperature(0.2).responseFormat(format)
                .extraBody(Map.of("enable_thinking",false)).build()).build();
    }
}
