package io.github.xw66.knowflowai.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

@Component
@Profile({"api", "worker"})
public class QdrantIndex {
    private final RestClient client;
    private final int dimensions;
    private final String collection;

    public QdrantIndex(@Value("${app.qdrant.url}") String url, @Value("${app.qdrant.api-key:}") String key,
            @Value("${app.embedding.dimensions}") int dimensions, @Value("${app.embedding.model}") String model,
            @Value("${app.embedding.base-url}") String baseUrl) {
        if (dimensions < 1 || dimensions > 65536) throw new IllegalArgumentException("Embedding 维度无效");
        this.dimensions = dimensions;
        this.collection = "knowflow_" + UUID.nameUUIDFromBytes((baseUrl + "\n" + model + "\n" + dimensions).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("api-key", key).build();
    }

    public String collection() { return collection; }

    public JsonNode inspectDocument(String targetCollection, long documentId, int limit) {
        if (targetCollection == null || !targetCollection.matches("knowflow_[a-f0-9]{32}") || documentId <= 0 || limit < 1 || limit > 2001)
            throw new IllegalArgumentException("对账范围无效");
        return client.post().uri("/collections/" + targetCollection + "/points/scroll")
                .body(Map.of("limit",limit,"with_vector",false,
                        "with_payload",List.of("document_id","knowledge_base_id","index_version","chunk_id"),
                        "filter",Map.of("must",List.of(Map.of("key","document_id","match",Map.of("value",documentId))))))
                .retrieve().body(JsonNode.class);
    }

    public JsonNode scroll(String targetCollection, String offset, int limit) {
        if (targetCollection == null || !targetCollection.matches("knowflow_[a-f0-9]{32}") || limit < 1 || limit > 100)
            throw new IllegalArgumentException("向量扫描范围无效");
        var body = new java.util.HashMap<String,Object>();
        body.put("limit", limit + 1); body.put("with_vector", false);
        body.put("with_payload", List.of("document_id","knowledge_base_id","index_version","chunk_id"));
        if (offset != null && !offset.isBlank()) body.put("offset", offset);
        return client.post().uri("/collections/" + targetCollection + "/points/scroll")
                .body(body).retrieve().body(JsonNode.class);
    }

    public void deleteDocument(String targetCollection, long documentId) {
        if (!targetCollection.matches("knowflow_[a-f0-9]{32}") || documentId <= 0) {
            throw new IllegalArgumentException("清理范围无效");
        }
        try {
            var result = client.post().uri("/collections/" + targetCollection + "/points/delete?wait=true")
                    .body(Map.of("filter", Map.of("must", List.of(Map.of("key", "document_id", "match", Map.of("value", documentId))))))
                    .retrieve().body(JsonNode.class);
            if (result == null || !"completed".equals(result.path("result").path("status").asText())) {
                throw new IllegalStateException("向量删除未确认完成");
            }
        } catch (HttpClientErrorException.NotFound exception) {
            // 集合不存在等同于该集合中已无此文档的向量。
        }
    }

    public JsonNode search(float[] vector, long knowledgeBaseId, int limit) {
        if (vector.length != dimensions) throw new IllegalArgumentException("查询向量维度不匹配");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("查询向量无效");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("查询向量为空");
        return client.post().uri("/collections/" + collection + "/points/query")
                .body(Map.of("query", vector, "limit", limit, "with_payload", true, "with_vector", false,
                        "filter", Map.of("must", List.of(Map.of("key", "knowledge_base_id", "match", Map.of("value", knowledgeBaseId))))))
                .retrieve().body(JsonNode.class);
    }

    public void upsert(List<Map<String, Object>> points) {
        String path = "/collections/" + collection;
        try {
            verify(client.get().uri(path).retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound exception) {
            try {
                client.put().uri(path).body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException conflict) {
                // 多 Worker 同时初始化时由后续读取确认配置；不覆盖已有集合。
                if (conflict.getStatusCode().value() != 409) throw conflict;
            }
            verify(client.get().uri(path).retrieve().body(JsonNode.class));
        }
        var result = client.put().uri(path + "/points?wait=true").body(Map.of("points", points)).retrieve().body(JsonNode.class);
        if (result == null || !"completed".equals(result.path("result").path("status").asText())) {
            throw new IllegalStateException("向量写入未确认完成");
        }
    }

    private void verify(JsonNode response) {
        var vectors = response.path("result").path("config").path("params").path("vectors");
        if (vectors.path("size").asInt() != dimensions || !"Cosine".equals(vectors.path("distance").asText())) {
            throw new IllegalStateException("Qdrant 集合维度或距离类型不匹配");
        }
    }
}
