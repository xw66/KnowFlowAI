package io.github.xw66.knowflowai.document;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentService {

    private static final String VISIBLE_DOCUMENTS = """
            SELECT d.id, d.knowledge_base_id, d.name, d.media_type, d.size_bytes, d.status,
                   d.index_version, d.active_index_version, t.id AS latest_task_id, t.status AS latest_task_status,
                   t.stage AS latest_task_stage, t.error_code, d.created_at, d.updated_at
            FROM document d
            JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
            JOIN knowledge_access km ON km.knowledge_base_id=kb.id
            JOIN app_user u ON u.id=km.user_id
            JOIN document_task t ON t.document_id=d.id AND t.index_version=d.index_version
            WHERE kb.id=:base AND kb.status='ACTIVE' AND km.user_id=:user AND u.status='ACTIVE' AND d.status<>'DELETED'
            """;

    private final JdbcClient jdbc;
    private final KnowledgeBaseService knowledge;
    private final DocumentStorage storage;
    private final TransactionTemplate transaction;
    private final TaskCache taskCache;
    private final RequestIdempotency idempotency;

    public DocumentService(JdbcClient jdbc, KnowledgeBaseService knowledge, DocumentStorage storage,
            PlatformTransactionManager transactionManager, TaskCache taskCache, RequestIdempotency idempotency) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactionManager);
        this.taskCache = taskCache;
        this.idempotency = idempotency;
    }

    public UploadResponse upload(long userId, long knowledgeBaseId, String idempotencyKey, MultipartFile upload) {
        knowledge.requireEditAccess(userId, knowledgeBaseId);
        return idempotency.execute("upload:" + knowledgeBaseId + ":" + userId + ":" + idempotencyKey,
                () -> uploadOnce(userId, knowledgeBaseId, idempotencyKey, upload));
    }

    private UploadResponse uploadOnce(long userId, long knowledgeBaseId, String idempotencyKey, MultipartFile upload) {
        var file = storage.store(upload);
        var registered = new AtomicBoolean();
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completionStatus) {
                        // 只在确定回滚时删除；提交结果未知时保留文件，防止删掉已提交文档。
                        if (completionStatus == STATUS_ROLLED_BACK) {
                            storage.deleteQuietly(file.key());
                        }
                    }
                });
                registered.set(true);
                knowledge.lockForEditing(userId, knowledgeBaseId);
                var existing = jdbc.sql("""
                        SELECT d.id AS document_id, t.id AS task_id, t.status, d.name, d.sha256, d.status AS document_status
                        FROM document d JOIN document_task t ON t.document_id = d.id AND t.index_version = 1
                        WHERE d.knowledge_base_id = :baseId AND d.uploaded_by = :userId AND d.idempotency_key = :key
                        """)
                        .param("baseId", knowledgeBaseId).param("userId", userId).param("key", idempotencyKey)
                        .query(ExistingUpload.class).optional();
                if (existing.isPresent()) {
                    var previous = existing.get();
                    if ("DELETED".equals(previous.documentStatus())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "原文档已删除，请使用新的幂等键上传");
                    }
                    if (!previous.name().equals(file.name()) || !previous.sha256().equals(file.sha256())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "该幂等键已用于其他文件或文件名");
                    }
                    storage.deleteQuietly(file.key());
                    return new UploadResponse(previous.documentId(), previous.taskId(), previous.status());
                }
                var documentKey = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO document (knowledge_base_id, uploaded_by, name, storage_key, sha256, media_type, size_bytes, idempotency_key)
                        VALUES (:baseId, :userId, :name, :storageKey, :sha256, :mediaType, :size, :key)
                        """)
                        .param("baseId", knowledgeBaseId).param("userId", userId).param("name", file.name())
                        .param("storageKey", file.key()).param("sha256", file.sha256()).param("mediaType", file.mediaType())
                        .param("size", file.size()).param("key", idempotencyKey).update(documentKey);
                long documentId = Objects.requireNonNull(documentKey.getKey()).longValue();
                var taskKey = new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO document_task (document_id) VALUES (:documentId)").param("documentId", documentId).update(taskKey);
                long taskId = Objects.requireNonNull(taskKey.getKey()).longValue();
                jdbc.sql("""
                        INSERT INTO outbox_event (task_id, event_type, payload)
                        VALUES (:taskId, 'DOCUMENT_UPLOADED', JSON_OBJECT('taskId', :taskId, 'indexVersion', 1))
                        """).param("taskId", taskId).update();
                return new UploadResponse(documentId, taskId, "PENDING");
            });
        } finally {
            if (!registered.get()) {
                storage.deleteQuietly(file.key());
            }
            // ponytail: 进程崩溃或提交结果未知可能留下孤立文件，后续以数据库引用对账回收。
        }
    }

    public TaskView task(long userId, long taskId) {
        if (!taskCache.usable()) return taskFromDatabase(userId, taskId);
        var access = jdbc.sql("SELECT t.cache_version, t.status " + VISIBLE_TASKS)
                .param("taskId", taskId).param("userId", userId).query(TaskAccess.class).optional()
                .orElseThrow(DocumentService::taskNotFound);
        // 终态直接从数据库返回，不能用缓存将完成或失败的任务退回进行中。
        if (!TaskCache.active(access.status())) return taskFromDatabase(userId, taskId);
        var cached = taskCache.get(taskId, access.cacheVersion());
        if (cached != null) return cached;
        var current = jdbc.sql(TASK_FIELDS + VISIBLE_TASKS + " AND t.cache_version=:version")
                .param("taskId", taskId).param("userId", userId).param("version", access.cacheVersion())
                .query(TaskView.class).optional();
        if (current.isEmpty()) return taskFromDatabase(userId, taskId);
        taskCache.put(taskId, access.cacheVersion(), current.get());
        return current.get();
    }

    private static final String TASK_FIELDS = """
                SELECT t.id AS task_id, d.id AS document_id, d.knowledge_base_id, d.name AS document_name,
                       t.status, t.stage, t.received_at, t.attempts, t.error_code, t.created_at, t.updated_at
                """;
    private static final String VISIBLE_TASKS = """
                FROM document_task t JOIN document d ON d.id = t.document_id
                JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
                JOIN knowledge_access km ON km.knowledge_base_id = kb.id
                JOIN app_user u ON u.id = km.user_id
                WHERE t.id = :taskId AND km.user_id = :userId AND u.status = 'ACTIVE'
                  AND kb.status = 'ACTIVE' AND d.status <> 'DELETED'
                """;

    private TaskView taskFromDatabase(long userId, long taskId) {
        return jdbc.sql(TASK_FIELDS + VISIBLE_TASKS)
                .param("taskId", taskId).param("userId", userId).query(TaskView.class).optional()
                .orElseThrow(DocumentService::taskNotFound);
    }

    private static ResponseStatusException taskNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
    }

    private record TaskAccess(long cacheVersion, String status) {}

    public java.util.List<DocumentView> list(long userId, long baseId, long afterId, int limit) {
        knowledge.get(userId, baseId);
        return jdbc.sql(VISIBLE_DOCUMENTS + " AND d.id>:after ORDER BY d.id LIMIT :limit")
                .param("user", userId).param("base", baseId).param("after", afterId).param("limit", limit)
                .query(DocumentView.class).list();
    }

    public UploadResponse reindex(long userId, long baseId, long documentId, String key) {
        knowledge.requireEditAccess(userId, baseId);
        return idempotency.execute("reindex:" + baseId + ":" + documentId + ":" + userId + ":" + key,
                () -> reindexOnce(userId, baseId, documentId, key));
    }

    private UploadResponse reindexOnce(long userId, long baseId, long documentId, String key) {
        return transaction.execute(status -> {
            knowledge.lockForEditing(userId, baseId);
            jdbc.sql("SELECT id FROM document WHERE id=:id AND knowledge_base_id=:base AND status<>'DELETED' FOR UPDATE")
                    .param("id",documentId).param("base",baseId).query(Long.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在或无权访问"));
            var previous = jdbc.sql("SELECT document_id, id AS task_id, status FROM document_task WHERE document_id=:id AND requested_by=:user AND reindex_key=:key")
                    .param("id",documentId).param("user",userId).param("key",key).query(UploadResponse.class).optional();
            if (previous.isPresent()) return previous.get();
            var current = get(userId,baseId,documentId);
            if (!java.util.Set.of("SUCCEEDED","FAILED").contains(current.latestTaskStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"文档已有未完成任务");
            }
            int version = Math.addExact(current.indexVersion(),1);
            jdbc.sql("UPDATE document SET index_version=:version, status=IF(active_index_version IS NULL,'PENDING','READY') WHERE id=:id")
                    .param("version",version).param("id",documentId).update();
            var holder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO document_task(document_id,index_version,requested_by,reindex_key) VALUES (:id,:version,:user,:key)")
                    .param("id",documentId).param("version",version).param("user",userId).param("key",key).update(holder);
            long taskId=Objects.requireNonNull(holder.getKey()).longValue();
            jdbc.sql("INSERT INTO outbox_event(task_id,event_type,payload) VALUES (:task,'DOCUMENT_UPLOADED',JSON_OBJECT('taskId',:task,'indexVersion',:version))")
                    .param("task",taskId).param("version",version).update();
            return new UploadResponse(documentId,taskId,"PENDING");
        });
    }

    public void delete(long userId, long baseId, long documentId) {
        transaction.executeWithoutResult(status -> {
            knowledge.lockForEditing(userId, baseId);
            String current = jdbc.sql("SELECT status FROM document WHERE id=:id AND knowledge_base_id=:base FOR UPDATE")
                    .param("id", documentId).param("base", baseId).query(String.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权访问"));
            if ("DELETED".equals(current)) return;
            jdbc.sql("UPDATE document SET status='DELETED', active_index_version=NULL WHERE id=:id")
                    .param("id", documentId).update();
            // 撤销任务租约，使删除前已开始的解析和向量写入无法提交成功状态。
            var changedTasks = jdbc.sql("SELECT id FROM document_task WHERE document_id=:id AND status NOT IN ('SUCCEEDED','FAILED') FOR UPDATE")
                    .param("id", documentId).query(Long.class).list();
            jdbc.sql("""
                    UPDATE document_task SET cache_version=cache_version+1, status='FAILED', error_code='DOCUMENT_DELETED',
                      lease_token=NULL, lease_until=NULL, next_attempt_at=NULL, finished_at=CURRENT_TIMESTAMP(6)
                    WHERE document_id=:id AND status NOT IN ('SUCCEEDED','FAILED')
                    """).param("id", documentId).update();
            changedTasks.forEach(taskCache::invalidateAfterChange);
            jdbc.sql("""
                    INSERT INTO vector_cleanup(document_id, collection_name)
                    SELECT id, vector_collection FROM document WHERE id=:id AND vector_collection IS NOT NULL
                    UNION
                    SELECT document_id, vector_collection FROM document_task WHERE document_id=:id AND vector_collection IS NOT NULL
                    """).param("id", documentId).update();
        });
    }

    public DocumentView get(long userId, long baseId, long documentId) {
        return jdbc.sql(VISIBLE_DOCUMENTS + " AND d.id=:document")
                .param("user", userId).param("base", baseId).param("document", documentId)
                .query(DocumentView.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权访问"));
    }

    public record DocumentView(long id, long knowledgeBaseId, String name, String mediaType, long sizeBytes, String status,
            int indexVersion, Integer activeIndexVersion, long latestTaskId, String latestTaskStatus, String latestTaskStage,
            String errorCode, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record UploadResponse(long documentId, long taskId, String status) {
    }

    private record ExistingUpload(long documentId, long taskId, String status, String name, String sha256, String documentStatus) {
    }

    public record TaskView(long taskId, long documentId, long knowledgeBaseId, String documentName, String status,
            String stage, LocalDateTime receivedAt, int attempts, String errorCode, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
