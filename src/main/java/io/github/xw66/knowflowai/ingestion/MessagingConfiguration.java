package io.github.xw66.knowflowai.ingestion;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@Profile({"api", "worker"})
@EnableScheduling
public class MessagingConfiguration {

    @Bean
    KafkaAdmin.NewTopics documentTopics(@Value("${app.messaging.document-topic}") String topic,
            @Value("${app.messaging.dead-letter-topic}") String deadLetterTopic) {
        return new KafkaAdmin.NewTopics(TopicBuilder.name(topic).partitions(3).replicas(1).build(),
                TopicBuilder.name(deadLetterTopic).partitions(3).replicas(1).build());
    }

    @Bean
    DefaultErrorHandler documentErrorHandler(KafkaTemplate<String, String> template,
            @Value("${app.messaging.dead-letter-topic}") String deadLetterTopic) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        // 无法持久化接收结果时持续重试；只有无效消息进入死信，死信发布失败也不能丢弃原消息。
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(IllegalArgumentException.class, false), true);
        return handler;
    }
}
