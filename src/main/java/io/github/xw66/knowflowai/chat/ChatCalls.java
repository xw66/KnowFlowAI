package io.github.xw66.knowflowai.chat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public ChatCalls(@Qualifier("fallbackChatModel") ObjectProvider<ChatModel> backup,
            @Value("${app.chat.retries:0}") int retries,@Value("${app.chat.first-token-timeout:PT8S}") Duration firstToken,
            @Value("${app.chat.idle-timeout:PT8S}") Duration idle,@Value("${app.chat.total-timeout:PT30S}") Duration total) {
        this.backup=backup; this.retries=retries; this.firstToken=firstToken; this.idle=idle; this.total=total;
        if(retries<0 || retries>2) throw new IllegalArgumentException("聊天重试次数必须为 0 到 2");
        for(var value:new Duration[]{firstToken,idle,total})
            if(value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(2))>0) throw new IllegalArgumentException("聊天期限配置无效");
    }
    public ChatResponse call(ChatModel primary,Prompt input) {
        long end=System.nanoTime()+total.toNanos();
        return single(primary,input,end).retryWhen(policy(new AtomicBoolean()))
                .onErrorResume(error->retryable(error) && backup.getIfAvailable()!=null,
                        error->single(backup.getIfAvailable(),input,end))
                .timeout(total).block();
    }
    private Mono<ChatResponse> single(ChatModel model,Prompt input,long end) {
        return Mono.fromCallable(()->model.call(options(model,input,false,end))).subscribeOn(Schedulers.boundedElastic());
    }
    public Flux<ChatResponse> stream(ChatModel primary,Prompt input) {
        return Flux.defer(()-> {
            long end=System.nanoTime()+total.toNanos();
            var started=new AtomicBoolean();
            return attempt(primary,input,end,started).retryWhen(policy(started))
                    .onErrorResume(error->!started.get() && retryable(error) && backup.getIfAvailable()!=null,
                            error->attempt(backup.getIfAvailable(),input,end,started))
                    .takeUntilOther(Mono.delay(total).then(Mono.error(new TimeoutException("聊天总期限已到"))));
        });
    }
    private Flux<ChatResponse> attempt(ChatModel model,Prompt input,long end,AtomicBoolean started) {
        return Flux.defer(()-> {
            long firstEnd=System.nanoTime()+firstToken.toNanos();
            return model.stream(options(model,input,true,end)).doOnNext(response-> {
                var result=response.getResult();
                if(response.hasToolCalls() || result!=null && (result.getOutput().getText()!=null && !result.getOutput().getText().isEmpty()
                        || result.getMetadata().getFinishReason()!=null && !result.getMetadata().getFinishReason().isBlank())) started.set(true);
            }).timeout(Mono.delay(firstToken),response->Mono.delay(started.get()?idle:Duration.ofNanos(Math.max(1,firstEnd-System.nanoTime()))));
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
}
