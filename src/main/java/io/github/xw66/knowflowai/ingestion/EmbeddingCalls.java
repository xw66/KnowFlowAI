package io.github.xw66.knowflowai.ingestion;

import java.util.List;
import java.util.UUID;
import io.github.xw66.knowflowai.observability.ModelCallLog;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingCalls {
    private final ModelCallLog log;
    private final String requestedModel;
    private final String endpoint;

    public EmbeddingCalls(ModelCallLog log,@Value("${app.embedding.model}") String requestedModel,@Value("${app.embedding.base-url}") String endpoint) {
        this.log=log; this.requestedModel=requestedModel;
        this.endpoint=endpoint;
    }

    public List<float[]> embed(EmbeddingModel model,List<String> texts,Long taskId) {
        long id=log.start(UUID.randomUUID().toString(),1,"EMBEDDING",taskId==null?"QUERY":"INDEX",false,null,requestedModel,taskId,endpoint);
        long started=System.nanoTime();
        try {
            var response=model.embedForResponse(texts);
            var usage=response.getMetadata().getUsage();
            var tokens=usage!=null && usage.getNativeUsage()!=null && usage.getPromptTokens()!=null && usage.getTotalTokens()!=null
                    && usage.getPromptTokens()>=0 && usage.getTotalTokens()>=0
                    ? new ModelCallLog.Tokens(usage.getPromptTokens(),0,usage.getTotalTokens()):null;
            // 向量维度校验或后续索引写入失败，不撤销已经完成的模型调用。
            log.finish(id,"COMPLETED",response.getMetadata().getModel(),tokens,(System.nanoTime()-started)/1_000_000,null);
            return response.getResults().stream().map(Embedding::getOutput).toList();
        } catch(RuntimeException error) {
            log.finish(id,"FAILED",null,null,(System.nanoTime()-started)/1_000_000,error.getClass().getSimpleName());
            throw error;
        }
    }
}
