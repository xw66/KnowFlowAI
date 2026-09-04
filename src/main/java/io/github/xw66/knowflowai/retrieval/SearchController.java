package io.github.xw66.knowflowai.retrieval;

import java.util.List;
import io.github.xw66.knowflowai.auth.AccountService.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/knowledge-bases/{id}/search")
public class SearchController {
    private final SearchService service;
    public SearchController(SearchService service) { this.service = service; }

    @PostMapping
    @io.swagger.v3.oas.annotations.Operation(summary = "知识库内检索", description = "mode 默认为 VECTOR，可选择 BM25。返回原文片段与引用位置，返回前重新验证权限及激活版本。BM25 不调用模型。")
    public ResponseEntity<List<SearchService.Hit>> search(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.search(account.id(), id, request.query(), request.topK() == null ? 5 : request.topK(),
                        request.mode()==null ? SearchService.Mode.VECTOR : request.mode()));
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(20) Integer topK, SearchService.Mode mode) {}
}
