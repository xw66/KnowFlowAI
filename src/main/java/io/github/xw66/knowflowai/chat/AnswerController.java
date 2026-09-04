package io.github.xw66.knowflowai.chat;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import io.github.xw66.knowflowai.retrieval.SearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge-bases/{id}/answers")
public class AnswerController {
    private final AnswerService service;
    private final AnswerStreamService streams;
    private final ConversationService conversations;
    public AnswerController(AnswerService service,AnswerStreamService streams,ConversationService conversations) {
        this.service=service; this.streams=streams; this.conversations=conversations;
    }

    @PostMapping(path="/stream",produces="text/event-stream")
    @io.swagger.v3.oas.annotations.Operation(summary="流式证据问答",description="delta 为未完成正文；仅 done 表示成功结束，error 表示失败。支持客户端断开取消。")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> stream(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id,@Valid @RequestBody AnswerRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
                .body(streams.stream(account.id(),id,request.question(),request.topK()==null?4:request.topK(),
                        request.mode()==null?SearchService.Mode.HYBRID:request.mode(),Boolean.TRUE.equals(request.rerank()),request.conversationId(),Boolean.TRUE.equals(request.rewrite())));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(summary="基于原文证据回答问题",description="默认 HYBRID，topK 默认 4、最多 8。返回可核验引用；无证据时不调用聊天模型。当前为同步接口。")
    public ResponseEntity<AnswerService.Answer> answer(@AuthenticationPrincipal Account account,@PathVariable @Positive long id,
            @Valid @RequestBody AnswerRequest request) {
        var turn=conversations.begin(account.id(),id,request.conversationId(),request.question());
        try {
            var answer=service.answer(account.id(),id,request.question(),request.topK()==null?4:request.topK(),
                    request.mode()==null?SearchService.Mode.HYBRID:request.mode(),Boolean.TRUE.equals(request.rerank()),turn,Boolean.TRUE.equals(request.rewrite()));
            conversations.complete(turn,answer.answer(),answer.citations(),answer.model(),answer.usage());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Conversation-Id",Long.toString(turn.conversationId()))
                    .header("X-Message-Id",Long.toString(turn.messageId())).body(answer);
        } catch(RuntimeException error) {
            conversations.terminateSafely(turn,"FAILED","",null,null,"ANSWER_FAILED");
            throw error;
        }
    }

    public record AnswerRequest(@NotBlank @Size(max=2000) String question,@Min(1) @Max(8) Integer topK,SearchService.Mode mode,Boolean rerank,@Positive Long conversationId,Boolean rewrite) {}
}
