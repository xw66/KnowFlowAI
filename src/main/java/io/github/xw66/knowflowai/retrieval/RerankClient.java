package io.github.xw66.knowflowai.retrieval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.xw66.knowflowai.observability.ModelCallLog;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("api")
@ConditionalOnProperty(name="app.rerank.enabled",havingValue="true")
public class RerankClient implements AutoCloseable {
    private final HttpClient client;
    private final URI url;
    private final String key;
    private final String model;
    private final Duration timeout;
    private final int candidates;
    private final ObjectMapper mapper;
    private final ModelCallLog log;

    public RerankClient(@Value("${app.rerank.url}") String url, @Value("${app.rerank.api-key}") String key,
            @Value("${app.rerank.model}") String model, @Value("${app.rerank.timeout}") Duration timeout,
            @Value("${app.rerank.candidates}") int candidates, ObjectMapper mapper, ModelCallLog log) {
        this.url=URI.create(url);
        if (!List.of("https","http").contains(this.url.getScheme()) || this.url.getHost()==null || this.url.getUserInfo()!=null
                || this.url.getFragment()!=null || key.isBlank() || !model.matches("[A-Za-z0-9._-]{1,100}")
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30))>0
                || timeout.toMillis()<1 || candidates<20 || candidates>50) throw new IllegalArgumentException("重排服务配置无效");
        this.key=key; this.model=model; this.timeout=timeout; this.candidates=candidates; this.mapper=mapper;
        this.log=log;
        this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public int candidates() { return candidates; }
    public String model() { return model; }

    public List<SearchService.Hit> rank(String query, List<SearchService.Hit> hits) throws IOException {
        if (hits.isEmpty() || hits.size()>candidates) throw new IllegalArgumentException("重排候选数量无效");
        var body=Map.of("model",model,"input",Map.of("query",query,"documents",hits.stream().map(SearchService.Hit::content).toList()),
                "parameters",Map.of("top_n",hits.size(),"return_documents",false));
        var request=HttpRequest.newBuilder(url).timeout(timeout).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        long id=log.start(java.util.UUID.randomUUID().toString(),1,"RERANK","PRIMARY",false,null,model);
        long started=System.nanoTime();
        String status="FAILED",errorType=null,actualModel=null;
        ModelCallLog.Tokens usage=null;
        try {
            var future=client.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),131072));
            HttpResponse<byte[]> response;
            try {
                // 等待完整响应体并设置总期限，避免只限制响应头而被慢速正文无限拖住。
                response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException exception) {
                future.cancel(true);
                throw new IOException("重排请求失败或超时");
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("重排请求已取消");
            }
            if (response.statusCode()!=200) throw new IOException("重排响应无效");
            var root=mapper.readTree(response.body());
            var total=root.path("usage").path("total_tokens");
            // 百炼重排仅返回总用量时，不把它伪造为输入或输出用量。
            if(total.isIntegralNumber() && total.canConvertToInt() && total.asInt()>=0) usage=new ModelCallLog.Tokens(null,null,total.asInt());
            if(root.path("model").isTextual()) actualModel=root.path("model").asText();
            var results=root.path("output").path("results");
            if (root.hasNonNull("code") || !results.isArray() || results.size()!=hits.size()) throw new IOException("重排结果不完整");
            var seen=new HashSet<Integer>();
            var ranks=new ArrayList<Rank>();
            for (var result : results) {
                var position=result.path("index");
                var scoreNode=result.path("relevance_score");
                int positionValue=position.asInt(-1);
                double score=scoreNode.asDouble(Double.NaN);
                if (!position.isIntegralNumber() || !position.canConvertToInt() || positionValue<0 || positionValue>=hits.size()
                        || !seen.add(positionValue) || !scoreNode.isNumber() || !Double.isFinite(score) || score<0 || score>1) {
                    throw new IOException("重排位置或分数无效");
                }
                ranks.add(new Rank(positionValue,score));
            }
            ranks.sort(Comparator.comparingDouble(Rank::score).reversed().thenComparingInt(Rank::index));
            status="COMPLETED";
            return ranks.stream().map(rank -> {
                var hit=hits.get(rank.index());
                return new SearchService.Hit(hit.chunkId(),hit.documentId(),hit.documentName(),hit.content(),hit.pageNumber(),hit.paragraphNumber(),rank.score());
            }).toList();
        } catch(IOException | RuntimeException error) {
            errorType=error.getClass().getSimpleName();
            status=Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED";
            throw error;
        } finally {
            log.finish(id,status,actualModel,usage,(System.nanoTime()-started)/1_000_000,errorType);
        }
    }

    @Override @PreDestroy public void close() { client.close(); }
    private record Rank(int index,double score) {}
}
