package io.github.xw66.knowflowai.ingestion;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("api")
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final String topic;

    public OutboxPublisher(JdbcClient jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager manager,
            @Value("${app.messaging.document-topic}") String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(manager);
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay}", initialDelayString = "${app.outbox.initial-delay}")
    public void publishPending() {
        for (int i = 0; i < 20; i++) {
            if (!publishNext()) {
                break;
            }
        }
    }

    public boolean publishNext() {
        String token = UUID.randomUUID().toString();
        var event = transaction.execute(status -> {
            var selected = jdbc.sql("""
                    SELECT id, task_id, payload FROM outbox_event
                    WHERE event_type = 'DOCUMENT_UPLOADED' AND
                      ((status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6))
                       OR (status = 'PUBLISHING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """).query(Event.class).optional();
            if (selected.isEmpty()) {
                return null;
            }
            jdbc.sql("""
                    UPDATE outbox_event SET status = 'PUBLISHING', lease_token = :token,
                        lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6)), attempts = attempts + 1
                    WHERE id = :id
                    """).param("token", token).param("id", selected.get().id()).update();
            return selected.get();
        });
        if (event == null) {
            return false;
        }
        try {
            // 网络等待不占用数据库事务；超时可能已发送，因此下游仍须处理重复消息。
            kafka.send(topic, Long.toString(event.taskId()), event.payload()).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retry(event.id(), token);
            return false;
        } catch (ExecutionException | TimeoutException | KafkaException | org.springframework.kafka.KafkaException exception) {
            retry(event.id(), token);
            return false;
        }
        int updated = jdbc.sql("""
                UPDATE outbox_event SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6), lease_token = NULL, lease_until = NULL
                WHERE id = :id AND status = 'PUBLISHING' AND lease_token = :token
                """).param("id", event.id()).param("token", token).update();
        LOG.atInfo().addKeyValue("outboxId", event.id()).addKeyValue("taskId", event.taskId())
                .addKeyValue("leaseMatched", updated == 1).log("Kafka 已确认文档事件");
        return true;
    }

    private void retry(long id, String token) {
        jdbc.sql("""
                UPDATE outbox_event SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
                    available_at = TIMESTAMPADD(SECOND, LEAST(300, POW(2, LEAST(attempts, 9))), CURRENT_TIMESTAMP(6))
                WHERE id = :id AND status = 'PUBLISHING' AND lease_token = :token
                """).param("id", id).param("token", token).update();
        LOG.atWarn().addKeyValue("outboxId", id).log("Kafka 发布失败，事件已安排退避重试");
    }

    private record Event(long id, long taskId, String payload) {
    }
}
