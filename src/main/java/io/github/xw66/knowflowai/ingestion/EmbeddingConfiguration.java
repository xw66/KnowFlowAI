package io.github.xw66.knowflowai.ingestion;

import java.time.Duration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "app.embedding.enabled", havingValue = "true")
public class EmbeddingConfiguration {
    @Bean
    EmbeddingModel embeddingModel(@Value("${app.embedding.api-key}") String key,
            @Value("${app.embedding.base-url}") String baseUrl, @Value("${app.embedding.model}") String model) {
        if (key.isBlank() || model.isBlank()) throw new IllegalArgumentException("请配置 Embedding 密钥和模型");
        return new OpenAiEmbeddingModel(OpenAiEmbeddingOptions.builder().apiKey(key).baseUrl(baseUrl)
                .model(model).timeout(Duration.ofSeconds(20)).maxRetries(0).build());
    }
}
