package io.github.xw66.knowflowai.ingestion;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("worker")
public class VectorCleanupProcessor {
    private final JdbcClient jdbc;
    private final QdrantIndex index;
    private final TransactionTemplate transaction;

    public VectorCleanupProcessor(JdbcClient jdbc, QdrantIndex index, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.index = index;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.cleanup.poll-delay:1000}", initialDelayString = "${app.cleanup.initial-delay:1000}")
    public void processNext() {
        String token = UUID.randomUUID().toString();
        Cleanup task = transaction.execute(status -> {
            var selected = jdbc.sql("""
                    SELECT c.document_id, c.collection_name FROM vector_cleanup c
                    JOIN document d ON d.id=c.document_id
                    WHERE d.status='DELETED' AND c.available_at<=CURRENT_TIMESTAMP(6)
                    ORDER BY c.available_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    """).query(Cleanup.class).optional();
            if (selected.isEmpty()) return null;
            var row = selected.get();
            jdbc.sql("""
                    UPDATE vector_cleanup SET lease_token=:token, available_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6))
                    WHERE document_id=:id AND collection_name=:collection
                    """).param("token", token).param("id", row.documentId()).param("collection", row.collectionName()).update();
            return row;
        });
        if (task == null) return;
        try {
            index.deleteDocument(task.collectionName(), task.documentId());
            // ponytail: 删除与外部写入没有分布式事务，保留墓碑每小时复查，后续对账再收敛清理频率。
            jdbc.sql("""
                    UPDATE vector_cleanup SET last_cleaned_at=CURRENT_TIMESTAMP(6), attempts=0, error_code=NULL,
                      available_at=TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)), lease_token=NULL
                    WHERE document_id=:id AND collection_name=:collection AND lease_token=:token
                    """).param("id", task.documentId()).param("collection", task.collectionName()).param("token", token).update();
        } catch (RuntimeException exception) {
            jdbc.sql("""
                    UPDATE vector_cleanup SET attempts=LEAST(attempts+1,30), error_code='VECTOR_CLEANUP_FAILED',
                      available_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts+1,9))),CURRENT_TIMESTAMP(6)), lease_token=NULL
                    WHERE document_id=:id AND collection_name=:collection AND lease_token=:token
                    """).param("id", task.documentId()).param("collection", task.collectionName()).param("token", token).update();
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("documentId", task.documentId())
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("向量清理失败，将自动重试");
        }
    }

    private record Cleanup(long documentId, String collectionName) {}
}
