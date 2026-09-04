package io.github.xw66.knowflowai.knowledge;

import java.util.List;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseService.KnowledgeBaseView create(@AuthenticationPrincipal Account account,
            @Valid @RequestBody NameRequest request) {
        return service.create(account.id(), request.name());
    }

    @GetMapping
    public List<KnowledgeBaseService.KnowledgeBaseView> list(@AuthenticationPrincipal Account account,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(account.id(), afterId, limit);
    }

    @GetMapping("/{id}")
    public KnowledgeBaseService.KnowledgeBaseView get(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id) {
        return service.get(account.id(), id);
    }

    @PutMapping("/{id}")
    public KnowledgeBaseService.KnowledgeBaseView rename(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @Valid @RequestBody NameRequest request) {
        return service.rename(account.id(), id, request.name());
    }

    @PutMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMember(@AuthenticationPrincipal Account account, @PathVariable @Positive long id,
            @PathVariable @Positive long memberId, @Valid @RequestBody MemberRequest request) {
        service.setMember(account.id(), id, memberId, request.role());
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Account account, @PathVariable @Positive long id,
            @PathVariable @Positive long memberId) {
        service.removeMember(account.id(), id, memberId);
    }

    @GetMapping("/{id}/members")
    public List<KnowledgeBaseService.MemberView> members(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterUserId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.members(account.id(), id, afterUserId, limit);
    }

    public record NameRequest(
            @NotBlank(message = "知识库名称不能为空") @Size(max = 128, message = "知识库名称不能超过 128 个字符") String name) {
    }

    public record MemberRequest(
            @NotBlank(message = "成员角色不能为空") @Pattern(regexp = "EDITOR|VIEWER", message = "成员角色只能为 EDITOR 或 VIEWER") String role) {
    }
}
