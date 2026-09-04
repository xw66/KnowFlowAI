package io.github.xw66.knowflowai.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.github.xw66.knowflowai.document.TaskCache;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reconcile")
public class ReconcileController {
    private final JdbcClient jdbc;
    private final TaskCache cache;

    public ReconcileController(JdbcClient jdbc, TaskCache cache) { this.jdbc=jdbc; this.cache=cache; }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="只读索引与任务对账", description="报告异常，不自动删除文件、向量或切换激活版本。")
    public ResponseEntity<Report> report() {
        var values=Map.of(
                "staleTaskLeases", count("SELECT COUNT(*) FROM document_task WHERE status='PROCESSING' AND lease_until IS NOT NULL AND lease_until<CURRENT_TIMESTAMP(6)"),
                "retryWaitingTasks", count("SELECT COUNT(*) FROM document_task WHERE status='RETRY_WAIT'"),
                "failedTasks", count("SELECT COUNT(*) FROM document_task WHERE status='FAILED'"),
                "readyDocumentsWithoutChunks", count("""
                        SELECT COUNT(*) FROM document d WHERE d.status='READY' AND d.active_index_version IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_id=d.id AND c.index_version=d.active_index_version)
                        """),
                "readyDocumentsWithoutVectorCollection", count("SELECT COUNT(*) FROM document WHERE status='READY' AND (vector_collection IS NULL OR active_index_version IS NULL)"),
                "readyDocumentsWithoutBm25Progress", count("""
                        SELECT COUNT(*) FROM document d LEFT JOIN bm25_index_progress p ON p.document_id=d.id
                        WHERE d.status='READY' AND d.active_index_version IS NOT NULL
                          AND (p.document_id IS NULL OR p.status<>'READY' OR p.index_version<>d.active_index_version)
                        """),
                "staleBm25Progress", count("""
                        SELECT COUNT(*) FROM bm25_index_progress p LEFT JOIN document d ON d.id=p.document_id
                        WHERE d.id IS NULL OR d.status='DELETED' AND p.status<>'CLEANED'
                        """),
                "orphanVectorCleanup", count("SELECT COUNT(*) FROM vector_cleanup c LEFT JOIN document d ON d.id=c.document_id WHERE d.id IS NULL")
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Report(Instant.now(),Duration.ofSeconds(90).toString(),values));
    }

    @PostMapping("/stale-tasks")
    @org.springframework.transaction.annotation.Transactional
    @io.swagger.v3.oas.annotations.Operation(summary="恢复过期任务租约", description="指定最多 100 个任务 ID，默认仅预览；显式 dryRun=false 才执行。按解析或向量阶段判断重试上限，逐项返回恢复或跳过原因。")
    public ResponseEntity<RepairResult> recoverStaleTasks(@Valid @RequestBody RepairRequest request) {
        boolean dryRun = !Boolean.FALSE.equals(request.dryRun());
        var results = new ArrayList<TaskRepair>();
        int changed = 0;
        for (long id : request.taskIds().stream().distinct().sorted().toList()) {
            var documentId = jdbc.sql("SELECT document_id FROM document_task WHERE id=:id")
                    .param("id", id).query(Long.class).optional();
            if (documentId.isEmpty()) { results.add(new TaskRepair(id,"SKIPPED","NOT_FOUND",null)); continue; }
            // 与删除、重建保持相同的文档→任务锁顺序；执行时读取当前状态，不信任先前预览。
            var document = jdbc.sql("SELECT status, index_version FROM document WHERE id=:id FOR UPDATE")
                    .param("id", documentId.get()).query(DocumentState.class).single();
            var task = jdbc.sql("""
                    SELECT status, stage, index_version, attempts, vector_attempts, max_attempts,
                      COALESCE(lease_until <= CURRENT_TIMESTAMP(6), FALSE) AS expired
                    FROM document_task WHERE id=:id FOR UPDATE
                    """).param("id", id).query(TaskState.class).single();
            String reason = "DELETED".equals(document.status()) ? "DOCUMENT_DELETED"
                    : document.indexVersion() != task.indexVersion() ? "OLD_VERSION"
                    : !"PROCESSING".equals(task.status()) ? "NOT_PROCESSING"
                    : !task.expired() ? "LEASE_NOT_EXPIRED"
                    : !List.of("PARSING","INDEXING").contains(task.stage() == null ? "" : task.stage()) ? "UNSUPPORTED_STAGE" : null;
            if (reason != null) { results.add(new TaskRepair(id,"SKIPPED",reason,null)); continue; }
            boolean vector = "INDEXING".equals(task.stage());
            boolean failed = (vector ? task.vectorAttempts() : task.attempts()) >= task.maxAttempts();
            String nextStatus = failed ? "FAILED" : "RETRY_WAIT";
            String error = failed ? (vector ? "VECTOR_RETRY_EXHAUSTED" : "RETRY_EXHAUSTED") : "LEASE_EXPIRED";
            if (!dryRun) {
                jdbc.sql("""
                        UPDATE document_task SET cache_version=cache_version+1, status=:status,
                          next_attempt_at=IF(:failed,NULL,CURRENT_TIMESTAMP(6)),
                          finished_at=IF(:failed,CURRENT_TIMESTAMP(6),NULL), error_code=:error,
                          error_message=NULL, lease_token=NULL, lease_until=NULL WHERE id=:id
                        """).param("status",nextStatus).param("failed",failed).param("error",error).param("id",id).update();
                cache.invalidateAfterChange(id);
                if (failed) jdbc.sql("UPDATE document SET status=IF(active_index_version IS NULL,'FAILED','READY') WHERE id=:id")
                        .param("id",documentId.get()).update();
                changed++;
            }
            results.add(new TaskRepair(id,dryRun ? "PREVIEW" : "RECOVERED",error,nextStatus));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RepairResult(Instant.now(),dryRun,changed,results));
    }

    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    public record Report(Instant generatedAt,String taskLeaseTimeout,Map<String,Long> counts) {}
    public record RepairRequest(@NotNull @Size(min=1,max=100) List<@NotNull @Positive Long> taskIds, Boolean dryRun) {}
    public record TaskRepair(long taskId, String outcome, String reason, String nextStatus) {}
    public record RepairResult(Instant repairedAt, boolean dryRun, int changedTasks, List<TaskRepair> tasks) {}
    private record DocumentState(String status, int indexVersion) {}
    private record TaskState(String status, String stage, int indexVersion, int attempts, int vectorAttempts, int maxAttempts, boolean expired) {}
}
