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
    @io.swagger.v3.oas.annotations.Operation(summary = "知识库内检索", description = "mode 默认为 VECTOR，可选择 BM25 或 HYBRID。HYBRID 可设置 rerank=true；响应头 X-Rerank-Status 标明是否实际重排，失败退回 RRF。X-Search-Score-Type 标明分数类型。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",headers={
            @io.swagger.v3.oas.annotations.headers.Header(name="X-Rerank-Status",description="NOT_REQUESTED / DISABLED / INSUFFICIENT_CANDIDATES / APPLIED / FALLBACK"),
            @io.swagger.v3.oas.annotations.headers.Header(name="X-Search-Score-Type",description="VECTOR / BM25 / RRF / RERANK"),
            @io.swagger.v3.oas.annotations.headers.Header(name="X-Rerank-Model",description="仅 APPLIED 时返回模型名")})
    public ResponseEntity<List<SearchService.Hit>> search(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @Valid @RequestBody SearchRequest request) {
        var mode=request.mode()==null ? SearchService.Mode.VECTOR : request.mode();
        var result=service.search(account.id(),id,request.query(),request.topK()==null ? 5 : request.topK(),mode,Boolean.TRUE.equals(request.rerank()));
        var response=ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Rerank-Status",result.rerankStatus().name())
                .header("X-Search-Score-Type",result.rerankStatus()==SearchService.RerankStatus.APPLIED ? "RERANK" : mode==SearchService.Mode.HYBRID ? "RRF" : mode.name());
        if (result.rerankModel()!=null) response.header("X-Rerank-Model",result.rerankModel());
        return response.body(result.hits());
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(20) Integer topK, SearchService.Mode mode, Boolean rerank) {}
}
