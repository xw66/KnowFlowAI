package io.github.xw66.knowflowai.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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

    public ReconcileController(JdbcClient jdbc) { this.jdbc=jdbc; }

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
    @io.swagger.v3.oas.annotations.Operation(summary="恢复过期任务租约", description="仅处理已过期的 PROCESSING 任务；未达到重试上限的任务回到 RETRY_WAIT，达到上限的任务标记 FAILED，不触碰文档激活版本或外部索引。")
    public ResponseEntity<RepairResult> recoverStaleTasks() {
        int changed = jdbc.sql("""
                UPDATE document_task
                SET cache_version=cache_version+1,
                    status=CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    next_attempt_at=CASE WHEN attempts >= max_attempts THEN NULL ELSE CURRENT_TIMESTAMP(6) END,
                    finished_at=CASE WHEN attempts >= max_attempts THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                    error_code=CASE WHEN attempts >= max_attempts THEN 'RETRY_EXHAUSTED' ELSE 'LEASE_EXPIRED' END,
                    lease_token=NULL, lease_until=NULL
                WHERE status='PROCESSING' AND lease_until IS NOT NULL AND lease_until<CURRENT_TIMESTAMP(6)
                """).update();
        return ResponseEntity.ok(new RepairResult(Instant.now(), changed));
    }

    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    public record Report(Instant generatedAt,String taskLeaseTimeout,Map<String,Long> counts) {}
    public record RepairResult(Instant repairedAt, int changedTasks) {}
}
