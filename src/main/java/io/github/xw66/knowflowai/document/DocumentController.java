package io.github.xw66.knowflowai.document;

import java.net.URI;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/knowledge-bases/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.Operation(summary = "上传文档并创建异步任务", description = "OWNER/EDITOR 可上传；同一文件重试时复用 Idempotency-Key。202 仅表示任务已创建。")
    public ResponseEntity<DocumentService.UploadResponse> upload(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}", message = "幂等键须为 8–128 位字母、数字或 . _ : -") String key,
            @RequestPart("file") MultipartFile file) {
        var result = service.upload(account.id(), id, key, file);
        return ResponseEntity.accepted().location(URI.create("/api/document-tasks/" + result.taskId())).body(result);
    }

    @GetMapping("/api/document-tasks/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "查询当前有权访问的文档任务")
    public DocumentService.TaskView task(@AuthenticationPrincipal Account account, @PathVariable @Positive long id) {
        return service.task(account.id(), id);
    }

    @GetMapping("/api/knowledge-bases/{id}/documents")
    @io.swagger.v3.oas.annotations.Operation(summary = "分页列出当前有权访问的文档", description = "包含最新处理任务及激活版本，不返回存储路径或正文。")
    public ResponseEntity<java.util.List<DocumentService.DocumentView>> list(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @jakarta.validation.constraints.PositiveOrZero long afterId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int limit) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.list(account.id(), id, afterId, limit));
    }

    @PostMapping("/api/knowledge-bases/{id}/documents/{documentId}/reindex")
    @io.swagger.v3.oas.annotations.Operation(summary = "重新处理文档并创建新索引版本", description = "OWNER/EDITOR 可调用，仅在前一任务终止后允许。重复请求复用 Idempotency-Key。旧激活版本保留到新版本成功。")
    public ResponseEntity<DocumentService.UploadResponse> reindex(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @PathVariable @Positive long documentId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}") String key) {
        var result=service.reindex(account.id(),id,documentId,key);
        return ResponseEntity.accepted().location(URI.create("/api/document-tasks/"+result.taskId())).body(result);
    }

    @GetMapping("/api/knowledge-bases/{id}/documents/{documentId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取文档元数据及最新任务状态")
    public ResponseEntity<DocumentService.DocumentView> get(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @PathVariable @Positive long documentId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.get(account.id(), id, documentId));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/knowledge-bases/{id}/documents/{documentId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "删除文档", description = "OWNER/EDITOR 可调用。立即隐藏并取消未完成任务，向量异步清理；重复删除返回 204。")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Account account,
            @PathVariable @Positive long id, @PathVariable @Positive long documentId) {
        service.delete(account.id(), id, documentId);
        return ResponseEntity.noContent().build();
    }
}
