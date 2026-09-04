package io.github.xw66.knowflowai.document;

import java.time.Duration;
import java.util.Set;

import io.github.xw66.knowflowai.cache.RedisCache;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskCache {
    private final RedisCache cache;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TaskCache(RedisCache cache, JdbcClient jdbc, ObjectMapper mapper) {
        this.cache = cache;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean usable() {
        return cache.usable();
    }

    public DocumentService.TaskView get(long id, long version) {
        String json = cache.get(key(id, version));
        if (json == null) return null;
        try {
            var value = mapper.readValue(json, DocumentService.TaskView.class);
            if (value != null && value.taskId() == id && value.documentId() > 0 && value.knowledgeBaseId() > 0
                    && value.documentName() != null && value.attempts() >= 0 && value.createdAt() != null
                    && value.updatedAt() != null && active(value.status())) return value;
        } catch (JacksonException exception) {
            LoggerFactory.getLogger(getClass()).warn("任务缓存格式无效，回源数据库");
        }
        return null;
    }

    public void put(long id, long version, DocumentService.TaskView value) {
        if (usable() && active(value.status())) {
            cache.put(key(id, version), mapper.writeValueAsString(value), Duration.ofSeconds(5));
        }
    }

    public void invalidateAfterChange(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("任务缓存失效必须与状态更新处于同一事务");
        }
        // 写入者已在同一条 UPDATE 中递增版本；当前读避免可重复读快照取得旧版本。
        long version = jdbc.sql("SELECT cache_version FROM document_task WHERE id=:id FOR UPDATE")
                .param("id", id).query(Long.class).single();
        cache.invalidateAfterCommit(key(id, version - 1));
    }

    public static boolean active(String status) {
        return status != null && Set.of("PENDING", "PROCESSING", "RETRY_WAIT").contains(status);
    }

    private static String key(long id, long version) {
        return "knowflow:task:v1:" + id + ":" + version;
    }
}
