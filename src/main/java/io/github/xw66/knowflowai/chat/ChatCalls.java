package io.github.xw66.knowflowai.chat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import io.github.xw66.knowflowai.observability.ModelCallLog;
import org.springframework.ai.chat.metadata.EmptyUsage;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Service
public class ChatCalls {
    private final ObjectProvider<ChatModel> backup;
    private final int retries;
    private final Duration firstToken,idle,total;
    private final ModelCallLog log;
    public ChatCalls(@Qualifier("fallbackChatModel") ObjectProvider<ChatModel> backup,
            @Value("${app.chat.retries:0}") int retries,@Value("${app.chat.first-token-timeout:PT8S}") Duration firstToken,
            @Value("${app.chat.idle-timeout:PT8S}") Duration idle,@Value("${app.chat.total-timeout:PT30S}") Duration total, ModelCallLog log) {
        this.backup=backup; this.retries=retries; this.firstToken=firstToken; this.idle=idle; this.total=total;
        this.log=log;
        if(retries<0 || retries>2) throw new IllegalArgumentException("聊天重试次数必须为 0 到 2");
        for(var value:new Duration[]{firstToken,idle,total})
            if(value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(2))>0) throw new IllegalArgumentException("聊天期限配置无效");
    }
    public ChatResponse call(ChatModel primary,Prompt input) {
        return call(primary,input,null);
    }
    public ChatResponse call(ChatModel primary,Prompt input,Long messageId) {
        long end=System.nanoTime()+total.toNanos();
        String invocation=UUID.randomUUID().toString();
        var attempts=new AtomicInteger();
        return single(primary,input,end,invocation,attempts,"PRIMARY",messageId).retryWhen(policy(new AtomicBoolean()))
                .onErrorResume(error->retryable(error) && backup.getIfAvailable()!=null,
                        error->single(backup.getIfAvailable(),input,end,invocation,attempts,"FALLBACK",messageId))
                .timeout(total).block();
    }
    private Mono<ChatResponse> single(ChatModel model,Prompt input,long end,String invocation,AtomicInteger attempts,String route,Long messageId) {
        return Mono.defer(()-> {
            var trace=new Attempt(log.start(invocation,attempts.incrementAndGet(),route,false,messageId,model.getDefaultOptions().getModel()),end,false);
            return Mono.fromCallable(()-> {
                if(System.nanoTime()>=end) throw new TimeoutException("聊天总期限已到");
                return model.call(options(model,input,false,end));
            }).doOnNext(trace::observe)
                    .doOnSuccess(response->trace.finish("COMPLETED",null)).doOnError(trace::failed).doOnCancel(trace::cancel);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    public Flux<ChatResponse> stream(ChatModel primary,Prompt input) {
        return stream(primary,input,null);
    }
    public Flux<ChatResponse> stream(ChatModel primary,Prompt input,Long messageId) {
        return Flux.defer(()-> {
            long end=System.nanoTime()+total.toNanos();
            var started=new AtomicBoolean();
            String invocation=UUID.randomUUID().toString();
            var attempts=new AtomicInteger();
            return attempt(primary,input,end,started,invocation,attempts,"PRIMARY",messageId).retryWhen(policy(started))
                    .onErrorResume(error->!started.get() && retryable(error) && backup.getIfAvailable()!=null,
                            error->attempt(backup.getIfAvailable(),input,end,started,invocation,attempts,"FALLBACK",messageId))
                    .takeUntilOther(Mono.delay(total).then(Mono.error(new TimeoutException("聊天总期限已到"))));
        });
    }
    private Flux<ChatResponse> attempt(ChatModel model,Prompt input,long end,AtomicBoolean started,String invocation,AtomicInteger attempts,String route,Long messageId) {
        return Flux.defer(()-> {
            var trace=new Attempt(log.start(invocation,attempts.incrementAndGet(),route,true,messageId,model.getDefaultOptions().getModel()),end,true);
            long firstEnd=System.nanoTime()+firstToken.toNanos();
            return Flux.defer(()->System.nanoTime()>=end ? Flux.<ChatResponse>error(new TimeoutException("聊天总期限已到"))
                    : model.stream(options(model,input,true,end))).doOnNext(response-> {
                trace.observe(response);
                var result=response.getResult();
                if(response.hasToolCalls() || result!=null && (result.getOutput().getText()!=null && !result.getOutput().getText().isEmpty()
                        || result.getMetadata().getFinishReason()!=null && !result.getMetadata().getFinishReason().isBlank())) started.set(true);
            }).timeout(Mono.delay(firstToken),response->Mono.delay(started.get()?idle:Duration.ofNanos(Math.max(1,firstEnd-System.nanoTime()))))
                    .doOnComplete(()->trace.finish("COMPLETED",null)).doOnError(trace::failed).doOnCancel(trace::cancel);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private Prompt options(ChatModel model,Prompt input,boolean streaming,long end) {
        var original=(OpenAiChatOptions)model.getDefaultOptions();
        Duration remaining=Duration.ofNanos(Math.max(1,end-System.nanoTime()));
        var builder=original.mutate().maxRetries(0).timeout(original.getTimeout()!=null && original.getTimeout().compareTo(remaining)<0?original.getTimeout():remaining);
        if(streaming) builder.responseFormat(OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.TEXT).build()).streamUsage(true);
        return new Prompt(input.getInstructions(),builder.build());
    }
    private Retry policy(AtomicBoolean started) {
        return Retry.backoff(retries,Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(1)).jitter(0.2)
                .filter(error->!started.get() && retryable(error)).onRetryExhaustedThrow((spec,signal)->signal.failure());
    }
    private static boolean retryable(Throwable error) {
        while((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause()!=null)
            error=error.getCause();
        if(error instanceof OpenAIServiceException service) return service.statusCode()==408 || service.statusCode()==429 || service.statusCode()>=500 && service.statusCode()<600;
        return error instanceof OpenAIIoException || error instanceof TimeoutException;
    }

    static boolean knownUsage(org.springframework.ai.chat.metadata.Usage value,boolean streaming) {
        // Spring AI 2.0.1 把缺失的流式 usage 合成为零；不能据此声称零消耗。
        return value!=null && !(value instanceof EmptyUsage) && value.getNativeUsage()!=null
                && value.getPromptTokens()!=null && value.getCompletionTokens()!=null && value.getTotalTokens()!=null
                && value.getPromptTokens()>=0 && value.getCompletionTokens()>=0 && value.getTotalTokens()>=0
                && (!streaming || value.getPromptTokens()>0 || value.getCompletionTokens()>0 || value.getTotalTokens()>0);
    }
    private final class Attempt {
        private final long id,end,started=System.nanoTime();
        private final boolean streaming;
        private boolean finished;
        private String actualModel;
        private ModelCallLog.Tokens usage;
        Attempt(long id,long end,boolean streaming) { this.id=id; this.end=end; this.streaming=streaming; }
        synchronized void observe(ChatResponse response) {
            if(finished) return;
            if(response.getMetadata().getModel()!=null && !response.getMetadata().getModel().isBlank()) actualModel=response.getMetadata().getModel();
            var value=response.getMetadata().getUsage();
            if(knownUsage(value,streaming)) {
                // 流式 usage 为累计值，仅保留最新值，不能按分片相加。
                usage=new ModelCallLog.Tokens(value.getPromptTokens(),value.getCompletionTokens(),value.getTotalTokens());
            }
        }
        void failed(Throwable error) {
            while((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause()!=null) error=error.getCause();
            finish("FAILED",error.getClass().getSimpleName());
        }
        void cancel() {
            boolean timeout=System.nanoTime()>=end;
            finish(timeout?"FAILED":"CANCELLED",timeout?"TimeoutException":null);
        }
        synchronized void finish(String status,String errorType) {
            if(finished) return;
            finished=true;
            long latency=Math.max(0,(System.nanoTime()-started)/1_000_000);
            // 结束记账不阻塞模型回调和超时线程；未写回的记录仍以 RUNNING 可见。
            Schedulers.boundedElastic().schedule(()->log.finish(id,status,actualModel,usage,latency,errorType));
        }
    }
}
