package io.github.xw66.knowflowai.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("worker")
@ConditionalOnProperty(name = "app.embedding.enabled", havingValue = "true")
public class VectorTaskProcessor {
    private final JdbcClient jdbc;
    private final EmbeddingModel model;
    private final QdrantIndex index;
    private final TransactionTemplate transaction;
    private final int dimensions;

    public VectorTaskProcessor(JdbcClient jdbc, EmbeddingModel model, QdrantIndex index,
            PlatformTransactionManager manager, @Value("${app.embedding.dimensions}") int dimensions) {
        this.jdbc = jdbc;
        this.model = model;
        this.index = index;
        this.transaction = new TransactionTemplate(manager);
        this.dimensions = dimensions;
    }

    @Scheduled(fixedDelayString = "${app.vector.poll-delay:1000}", initialDelayString = "${app.vector.initial-delay:1000}")
    public void processNext() {
        String token = UUID.randomUUID().toString();
        Task task = transaction.execute(status -> {
            var selected = jdbc.sql("""
                    SELECT t.id, t.document_id, t.index_version, t.vector_cursor, t.vector_attempts,
                        t.max_attempts, d.knowledge_base_id, d.name
                    FROM document_task t JOIN document d ON d.id = t.document_id
                    WHERE d.status <> 'DELETED' AND d.index_version = t.index_version
                      AND (t.vector_collection IS NULL OR t.vector_collection = :collection)
                      AND ((t.status = 'PENDING' AND t.stage = 'CHUNKED')
                        OR (t.status = 'RETRY_WAIT' AND t.stage = 'INDEXING' AND t.next_attempt_at <= CURRENT_TIMESTAMP(6))
                        OR (t.status = 'PROCESSING' AND t.stage = 'INDEXING' AND t.lease_until <= CURRENT_TIMESTAMP(6)))
                    ORDER BY t.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """).param("collection", index.collection()).query(Task.class).optional();
            if (selected.isEmpty()) return null;
            var value = selected.get();
            if (value.vectorAttempts() >= value.maxAttempts()) {
                jdbc.sql("UPDATE document_task SET status = 'FAILED', error_code = 'VECTOR_RETRY_EXHAUSTED', lease_token = NULL, lease_until = NULL, finished_at = CURRENT_TIMESTAMP(6) WHERE id = :id")
                        .param("id", value.id()).update();
                jdbc.sql("UPDATE document SET status = IF(active_index_version IS NULL, 'FAILED', 'READY') WHERE id = :id AND status <> 'DELETED'").param("id", value.documentId()).update();
                return null;
            }
            jdbc.sql("""
                    UPDATE document_task SET status = 'PROCESSING', stage = 'INDEXING', vector_attempts = vector_attempts + 1,
                        vector_collection = :collection, lease_token = :token,
                        lease_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP(6)), next_attempt_at = NULL WHERE id = :id
                    """).param("collection", index.collection()).param("token", token).param("id", value.id()).update();
            return value;
        });
        if (task == null) return;
        try {
            var chunks = jdbc.sql("SELECT id, chunk_index, paragraph_number, page_number, content FROM document_chunk WHERE document_id = :id AND index_version = :version AND chunk_index > :cursor ORDER BY chunk_index LIMIT 16")
                    .param("id", task.documentId()).param("version", task.indexVersion()).param("cursor", task.vectorCursor()).query(Chunk.class).list();
            if (chunks.isEmpty()) throw new IllegalStateException("缺少待索引分块");
            var vectors = model.embed(chunks.stream().map(Chunk::content).toList());
            if (vectors.size() != chunks.size()) throw new IllegalStateException("Embedding 数量不匹配");
            var points = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                float[] vector = vectors.get(i);
                if (vector.length != dimensions) throw new IllegalStateException("Embedding 维度不匹配");
                double norm = 0;
                for (float number : vector) {
                    if (!Float.isFinite(number)) throw new IllegalStateException("Embedding 非有限值");
                    norm += (double) number * number;
                }
                if (norm == 0) throw new IllegalStateException("Embedding 不能是零向量");
                var payload = new HashMap<String, Object>();
                payload.put("document_id", task.documentId()); payload.put("knowledge_base_id", task.knowledgeBaseId());
                payload.put("index_version", task.indexVersion()); payload.put("chunk_id", chunk.id());
                payload.put("document_name", task.name()); payload.put("paragraph_number", chunk.paragraphNumber());
                if (chunk.pageNumber() != null) payload.put("page_number", chunk.pageNumber());
                // 正文留在 MySQL，检索阶段重新校验权限后才返回，Qdrant 只保存定位元数据。
                String pointId = UUID.nameUUIDFromBytes((task.documentId() + ":" + task.indexVersion() + ":" + chunk.chunkIndex()).getBytes(StandardCharsets.UTF_8)).toString();
                points.add(Map.of("id", pointId, "vector", vector, "payload", payload));
            }
            index.upsert(points);
            int cursor = chunks.getLast().chunkIndex();
            transaction.executeWithoutResult(status -> {
                if (!owns(task.id(), token)) return;
                boolean complete = !jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_chunk WHERE document_id = :id AND index_version = :version AND chunk_index > :cursor)")
                        .param("id", task.documentId()).param("version", task.indexVersion()).param("cursor", cursor).query(Boolean.class).single();
                jdbc.sql("""
                        UPDATE document_task SET vector_cursor = :cursor, vector_attempts = 0, status = :status, stage = :stage,
                          lease_token = NULL, lease_until = NULL, error_code = NULL,
                          finished_at = IF(:complete, CURRENT_TIMESTAMP(6), NULL) WHERE id = :id
                        """).param("cursor", cursor).param("status", complete ? "SUCCEEDED" : "PENDING")
                        .param("stage", complete ? "INDEXED" : "CHUNKED").param("complete", complete).param("id", task.id()).update();
                if (complete) jdbc.sql("UPDATE document SET status = 'READY', active_index_version = :version, vector_collection = :collection WHERE id = :id AND status <> 'DELETED' AND index_version = :version")
                        .param("version", task.indexVersion()).param("collection", index.collection()).param("id", task.documentId()).update();
            });
        } catch (RuntimeException exception) {
            // 网络或提交结果未知时保留进度；稳定 point ID 使重放成为覆盖写，不能提前激活索引。
            transaction.executeWithoutResult(status -> {
                if (!owns(task.id(), token)) return;
                boolean failed = task.vectorAttempts() + 1 >= task.maxAttempts();
                jdbc.sql("""
                        UPDATE document_task SET status = :status, error_code = 'VECTOR_WRITE_FAILED', lease_token = NULL, lease_until = NULL,
                          next_attempt_at = IF(:failed, NULL, TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(6))),
                          finished_at = IF(:failed, CURRENT_TIMESTAMP(6), NULL) WHERE id = :id
                        """).param("status", failed ? "FAILED" : "RETRY_WAIT").param("failed", failed).param("id", task.id()).update();
                if (failed) jdbc.sql("UPDATE document SET status = IF(active_index_version IS NULL, 'FAILED', 'READY') WHERE id = :id AND status <> 'DELETED'").param("id", task.documentId()).update();
            });
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("taskId", task.id())
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("向量写入失败，已保存重试状态");
        }
    }

    private boolean owns(long id, String token) {
        return jdbc.sql("SELECT id FROM document_task WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token FOR UPDATE")
                .param("id", id).param("token", token).query(Long.class).optional().isPresent();
    }
    private record Task(long id, long documentId, int indexVersion, int vectorCursor, int vectorAttempts, int maxAttempts, long knowledgeBaseId, String name) {}
    private record Chunk(long id, int chunkIndex, int paragraphNumber, Integer pageNumber, String content) {}
}
