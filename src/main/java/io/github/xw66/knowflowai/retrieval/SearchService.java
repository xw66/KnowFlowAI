package io.github.xw66.knowflowai.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import io.github.xw66.knowflowai.ingestion.QdrantIndex;
import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class SearchService {
    private final JdbcClient jdbc;
    private final KnowledgeBaseService bases;
    private final ObjectProvider<EmbeddingModel> models;
    private final ObjectProvider<QdrantIndex> indexes;
    private final TransactionTemplate transaction;

    public SearchService(JdbcClient jdbc, KnowledgeBaseService bases, ObjectProvider<EmbeddingModel> models,
            ObjectProvider<QdrantIndex> indexes, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.bases = bases; this.models = models; this.indexes = indexes;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<Hit> search(long userId, long baseId, String query, int topK) {
        bases.get(userId, baseId);
        var model = models.getIfAvailable();
        var index = indexes.getIfAvailable();
        if (model == null || index == null) throw unavailable();
        JsonNode points;
        try {
            // 限定候选规模，避免旧版本或无效索引导致无限扫描；候选不足时允许少于 topK。
            var response = index.search(model.embed(query), baseId, 200);
            points = response.path("result").path("points");
            if (!points.isArray() || points.size() > 200) throw new IllegalStateException("向量响应格式无效");
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn()
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("向量检索不可用");
            throw unavailable();
        }
        return transaction.execute(status -> {
            // 网络调用后重新授权；短共享锁与成员修改所用知识库行锁互斥。
            jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id", baseId).query(Long.class).optional();
            bases.get(userId, baseId);
            var hits = new ArrayList<Hit>();
            var seen = new HashSet<Long>();
            for (JsonNode point : points) {
                var payload = point.path("payload");
                long chunkId = payload.path("chunk_id").asLong(-1);
                long documentId = payload.path("document_id").asLong(-1);
                int version = payload.path("index_version").asInt(-1);
                double score = point.path("score").asDouble(Double.NaN);
                if (payload.path("knowledge_base_id").asLong(-1) != baseId || chunkId <= 0 || !Double.isFinite(score)) continue;
                var candidate = jdbc.sql("""
                        SELECT c.id AS chunk_id, d.id AS document_id, d.name AS document_name,
                            c.content, c.page_number, c.paragraph_number
                        FROM document_chunk c JOIN document d ON d.id=c.document_id
                        JOIN knowledge_base kb ON kb.id=d.knowledge_base_id
                        JOIN knowledge_member km ON km.knowledge_base_id=kb.id AND km.user_id=:user
                        JOIN app_user u ON u.id=km.user_id
                        WHERE c.id=:chunk AND d.id=:document AND kb.id=:base AND kb.status='ACTIVE' AND u.status='ACTIVE'
                            AND d.status='READY' AND c.index_version=d.active_index_version
                            AND c.index_version=:version AND d.vector_collection=:collection
                        """).param("chunk", chunkId).param("document", documentId).param("base", baseId)
                        .param("user", userId).param("version", version).param("collection", index.collection())
                        .query((rs, row) -> new Hit(rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("document_name"),
                                rs.getString("content"), rs.getObject("page_number", Integer.class), rs.getInt("paragraph_number"), score)).optional();
                if (candidate.isPresent() && seen.add(chunkId)) hits.add(candidate.get());
                if (hits.size() == topK) break;
            }
            return List.copyOf(hits);
        });
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "向量检索暂时不可用，请检查模型与索引配置");
    }
    public record Hit(long chunkId, long documentId, String documentName, String content,
                      Integer pageNumber, int paragraphNumber, double score) {}
}
