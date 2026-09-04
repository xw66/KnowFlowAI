package io.github.xw66.knowflowai.chat;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversations;
    public ConversationController(ConversationService conversations) { this.conversations=conversations; }
    @GetMapping
    public ResponseEntity<List<ConversationService.Conversation>> list(@AuthenticationPrincipal Account account,
            @RequestParam(defaultValue="0") @PositiveOrZero long afterId,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(conversations.list(account.id(),afterId,limit));
    }
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<ConversationService.Message>> messages(@AuthenticationPrincipal Account account,@PathVariable @Positive long id,
            @RequestParam(defaultValue="0") @PositiveOrZero long afterId,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(conversations.messages(account.id(),id,afterId,limit));
    }
}
