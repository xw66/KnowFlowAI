package io.github.xw66.knowflowai.ingestion;

import java.io.IOException;
import java.util.List;
import io.github.xw66.knowflowai.retrieval.LuceneIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@ConditionalOnProperty(name="app.bm25.enabled", havingValue="true")
public class Bm25TaskProcessor {
    private final JdbcClient jdbc;
    private final LuceneIndex index;

    public Bm25TaskProcessor(JdbcClient jdbc, LuceneIndex index) { this.jdbc=jdbc; this.index=index; }

    @Scheduled(fixedDelayString="${app.bm25.poll-delay:1000}", initialDelayString="${app.bm25.initial-delay:1000}")
    public synchronized void processNext() {
        // 文件锁保证同目录单 Writer；本方法串行，使删除清理不会被本进程更早的写入覆盖。
        String instance=index.instanceId();
        var selected=jdbc.sql("""
                SELECT d.id, d.knowledge_base_id, COALESCE(d.active_index_version,0) AS version, d.status
                FROM document d LEFT JOIN bm25_index_progress p ON p.document_id=d.id
                WHERE ((d.status='READY' AND d.active_index_version IS NOT NULL) OR d.status='DELETED')
                  AND (p.document_id IS NULL OR p.instance_id<>:instance OR p.index_version<>COALESCE(d.active_index_version,0)
                    OR (d.status='READY' AND p.status<>'READY') OR (d.status='DELETED' AND p.status<>'CLEANED'))
                  AND (p.document_id IS NULL OR p.available_at<=CURRENT_TIMESTAMP(6)
                    OR p.instance_id<>:instance OR p.index_version<>COALESCE(d.active_index_version,0))
                ORDER BY d.id LIMIT 1
                """).param("instance",instance).query(Task.class).optional();
        if (selected.isEmpty()) return;
        var task=selected.get();
        try {
            boolean deleted="DELETED".equals(task.status());
            var chunks=deleted ? List.<LuceneIndex.Chunk>of() : jdbc.sql("""
                    SELECT id,content FROM document_chunk WHERE document_id=:id AND index_version=:version ORDER BY chunk_index
                    """).param("id",task.id()).param("version",task.version()).query(LuceneIndex.Chunk.class).list();
            if (!deleted && chunks.isEmpty()) throw new IllegalStateException("文档缺少可重建分块");
            index.replace(task.id(),task.knowledgeBaseId(),task.version(),chunks);
            jdbc.sql("""
                    INSERT INTO bm25_index_progress(document_id,index_version,instance_id,status,committed_at)
                    VALUES (:id,:version,:instance,:status,CURRENT_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE index_version=:version,instance_id=:instance,status=:status,
                      committed_at=CURRENT_TIMESTAMP(6),error_code=NULL,attempts=0,available_at=CURRENT_TIMESTAMP(6)
                    """).param("id",task.id()).param("version",task.version()).param("instance",instance)
                    .param("status",deleted ? "CLEANED" : "READY").update();
        } catch (IOException | RuntimeException exception) {
            // 提交结果未知也不能发布 READY；重放整组替换恢复索引与数据库进度。
            jdbc.sql("""
                    INSERT INTO bm25_index_progress(document_id,index_version,instance_id,status,attempts,available_at,error_code)
                    VALUES (:id,:version,:instance,'RETRY_WAIT',1,TIMESTAMPADD(SECOND,10,CURRENT_TIMESTAMP(6)),'BM25_WRITE_FAILED')
                    ON DUPLICATE KEY UPDATE index_version=:version,instance_id=:instance,status='RETRY_WAIT',
                      available_at=TIMESTAMPADD(SECOND,LEAST(300,10*POW(2,LEAST(attempts,5))),CURRENT_TIMESTAMP(6)),
                      attempts=LEAST(attempts+1,30),error_code='BM25_WRITE_FAILED'
                    """).param("id",task.id()).param("version",task.version()).param("instance",instance).update();
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("documentId",task.id())
                    .addKeyValue("exceptionType",exception.getClass().getSimpleName()).log("BM25 写入失败，等待重试");
        }
    }

    private record Task(long id, long knowledgeBaseId, int version, String status) {}
}
