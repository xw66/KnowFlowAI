package io.github.xw66.knowflowai.ingestion;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.github.xw66.knowflowai.document.DocumentStorage;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("worker")
public class TextTaskProcessor {
    private final JdbcClient jdbc;
    private final DocumentStorage storage;
    private final TransactionTemplate transaction;

    public TextTaskProcessor(JdbcClient jdbc, DocumentStorage storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-delay:1000}",
            initialDelayString = "${app.processing.initial-delay:1000}")
    public void processNext() {
        String token = UUID.randomUUID().toString();
        Task task = transaction.execute(status -> {
            var selected = jdbc.sql("""
                    SELECT t.id, t.document_id, t.index_version, t.attempts, t.max_attempts, d.storage_key, d.sha256, d.media_type
                    FROM document_task t JOIN document d ON d.id = t.document_id
                    WHERE d.status <> 'DELETED' AND d.index_version = t.index_version
                      AND t.received_at IS NOT NULL
                      AND ((t.status = 'PENDING' AND t.stage = 'QUEUED')
                        OR (t.status = 'RETRY_WAIT' AND t.stage = 'PARSING' AND t.next_attempt_at <= CURRENT_TIMESTAMP(6))
                        OR (t.status = 'PROCESSING' AND t.stage = 'PARSING' AND t.lease_until <= CURRENT_TIMESTAMP(6)))
                    ORDER BY t.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """).query(Task.class).optional();
            if (selected.isEmpty()) return null;
            Task value = selected.get();
            if (value.attempts() >= value.maxAttempts()) {
                jdbc.sql("UPDATE document_task SET status = 'FAILED', error_code = 'RETRY_EXHAUSTED', lease_token = NULL, lease_until = NULL, finished_at = CURRENT_TIMESTAMP(6) WHERE id = :id")
                        .param("id", value.id()).update();
                jdbc.sql("UPDATE document SET status = IF(active_index_version IS NULL, 'FAILED', 'READY') WHERE id = :id AND status <> 'DELETED'").param("id", value.documentId()).update();
                return null;
            }
            jdbc.sql("""
                    UPDATE document_task SET status = 'PROCESSING', stage = 'PARSING', attempts = attempts + 1,
                        lease_token = :token, lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6)),
                        started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6)), next_attempt_at = NULL
                    WHERE id = :id
                    """).param("token", token).param("id", value.id()).update();
            jdbc.sql("UPDATE document SET status = IF(active_index_version IS NULL, 'PROCESSING', 'READY') WHERE id = :id AND status <> 'DELETED'").param("id", value.documentId()).update();
            return value;
        });
        if (task == null) return;
        try {
            byte[] bytes = storage.read(task.storageKey());
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(task.sha256())) {
                throw new IllegalArgumentException("文件摘要不匹配");
            }
            var chunks = DocumentParser.parse(bytes, task.mediaType());
            transaction.executeWithoutResult(status -> {
                // 租约令牌加行锁隔离旧执行者；分块与状态一次提交，重试不会产生半成品。
                if (!ownsLease(task, token)) return;
                jdbc.sql("DELETE FROM document_chunk WHERE document_id = :id AND index_version = :version")
                        .param("id", task.documentId()).param("version", task.indexVersion()).update();
                for (int i = 0; i < chunks.size(); i++) {
                    var chunk = chunks.get(i);
                    jdbc.sql("INSERT INTO document_chunk(document_id, index_version, chunk_index, paragraph_number, page_number, content) VALUES (:id, :version, :position, :paragraph, :page, :content)")
                            .param("id", task.documentId()).param("version", task.indexVersion()).param("position", i)
                            .param("paragraph", chunk.paragraphNumber()).param("page", chunk.pageNumber(), java.sql.Types.INTEGER)
                            .param("content", chunk.content()).update();
                }
                jdbc.sql("UPDATE document_task SET status = 'PENDING', stage = 'CHUNKED', lease_token = NULL, lease_until = NULL, error_code = NULL, error_message = NULL WHERE id = :id")
                        .param("id", task.id()).update();
            });
        } catch (IOException | IllegalArgumentException exception) {
            transaction.executeWithoutResult(status -> {
                if (!ownsLease(task, token)) return;
                boolean failed = exception instanceof IllegalArgumentException || task.attempts() + 1 >= task.maxAttempts();
                jdbc.sql("""
                        UPDATE document_task SET status = :status, error_code = :error, error_message = NULL,
                          lease_token = NULL, lease_until = NULL,
                          next_attempt_at = IF(:failed, NULL, TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(6))),
                          finished_at = IF(:failed, CURRENT_TIMESTAMP(6), NULL) WHERE id = :id
                        """).param("status", failed ? "FAILED" : "RETRY_WAIT")
                        .param("error", exception instanceof IllegalArgumentException ? "INVALID_CONTENT" : "FILE_READ_FAILED")
                        .param("failed", failed).param("id", task.id()).update();
                if (failed) jdbc.sql("UPDATE document SET status = IF(active_index_version IS NULL, 'FAILED', 'READY') WHERE id = :id AND status <> 'DELETED'").param("id", task.documentId()).update();
            });
            LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("taskId", task.id()).log("文档解析失败，已记录任务状态");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean ownsLease(Task task, String token) {
        return jdbc.sql("SELECT id FROM document_task WHERE id = :id AND status = 'PROCESSING' AND lease_token = :token FOR UPDATE")
                .param("id", task.id()).param("token", token).query(Long.class).optional().isPresent();
    }

    public static List<Chunk> split(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        var result = new ArrayList<Chunk>();
        int paragraph = 0;
        // ponytail: 按空行定义原文段落，每块最多 800 个 Unicode 码点；后续评测再引入结构化 Markdown 切分。
        for (String part : text.split("\\n[\\t ]*\\n+")) {
            if (part.isBlank()) continue;
            paragraph++;
            String content = part.strip();
            int remaining = content.codePointCount(0, content.length());
            for (int start = 0; start < content.length();) {
                int length = Math.min(800, remaining);
                int end = content.offsetByCodePoints(start, length);
                result.add(new Chunk(paragraph, content.substring(start, end)));
                if (result.size() > 20000) throw new IllegalArgumentException("分块数量超过限制");
                start = end;
                remaining -= length;
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有可提取文本");
        return result;
    }

    public record Chunk(int paragraphNumber, String content) {}
    private record Task(long id, long documentId, int indexVersion, int attempts, int maxAttempts, String storageKey, String sha256, String mediaType) {}
}
