package io.github.xw66.knowflowai.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("worker")
public class DocumentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentWorker.class);
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public DocumentWorker(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(id = "document-worker", topics = "${app.messaging.document-topic}",
            groupId = "${app.messaging.worker-group}")
    @Transactional
    public void receive(@Payload(required = false) String payload) {
        UploadEvent event;
        try {
            if (payload == null || payload.length() > 2048) {
                throw new IllegalArgumentException();
            }
            event = mapper.readValue(payload, UploadEvent.class);
            if (event == null || event.taskId() <= 0 || event.indexVersion() <= 0) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("文档事件格式无效");
        }
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_task WHERE id = :id AND index_version = :version)")
                .param("id", event.taskId()).param("version", event.indexVersion()).query(Boolean.class).single()) {
            throw new IllegalArgumentException("文档事件对应任务或版本不存在");
        }
        // 接收标记与事务提交先于消费位点提交；重放只确认，不重置已接收或已处理任务。
        int updated = jdbc.sql("""
                UPDATE document_task SET received_at = CURRENT_TIMESTAMP(6), stage = 'QUEUED'
                WHERE id = :id AND index_version = :version AND status = 'PENDING' AND received_at IS NULL
                """).param("id", event.taskId()).param("version", event.indexVersion()).update();
        LOG.atInfo().addKeyValue("taskId", event.taskId()).addKeyValue("firstReceipt", updated == 1)
                .log("Worker 已接收文档任务，等待解析模块处理");
    }

    private record UploadEvent(long taskId, int indexVersion) {
    }
}
