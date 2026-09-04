package io.github.xw66.knowflowai.observability;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/model-calls")
public class ModelCallController {
    private final JdbcClient jdbc;
    public ModelCallController(JdbcClient jdbc) { this.jdbc=jdbc; }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(summary="管理员查询模型调用尝试",description="覆盖聊天、查询改写、Embedding 和 Rerank；不包含提示词或回答。COMPLETED 不代表索引写入或回答引用校验通过。Rerank 可能只有总 Token，输入和输出用量保持未知。")
    public ResponseEntity<List<CallView>> list(@RequestParam(defaultValue="0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jdbc.sql("""
                SELECT id,invocation_id,attempt_number,call_type,route,streaming,message_id,task_id,requested_model,actual_model,
                       status,input_tokens,output_tokens,total_tokens,usage_known,latency_ms,error_type,started_at,finished_at
                FROM model_call WHERE id>:after ORDER BY id LIMIT :limit
                """).param("after",afterId).param("limit",limit).query(CallView.class).list());
    }

    @GetMapping("/summary")
    @io.swagger.v3.oas.annotations.Operation(summary="管理员汇总模型调用",description="默认最近 24 小时，最多 31 天，按开始时间统计。Token 合计仅包含已知 usage，RUNNING/未知用量不能视为零成本。")
    public ResponseEntity<Summary> summary(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if(to==null) to=Instant.now();
        if(from==null) from=to.minus(Duration.ofDays(1));
        if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"统计时间范围须大于零且不超过 31 天");
        var result=jdbc.sql("""
                SELECT COUNT(*) AS attempts,COUNT(CASE WHEN status='COMPLETED' THEN 1 END) AS completed,
                  COUNT(CASE WHEN status='FAILED' THEN 1 END) AS failed,COUNT(CASE WHEN status='CANCELLED' THEN 1 END) AS cancelled,
                  COUNT(CASE WHEN status='RUNNING' THEN 1 END) AS running,
                  COUNT(CASE WHEN usage_known THEN 1 END) AS known_usage_calls,
                  COUNT(CASE WHEN NOT usage_known THEN 1 END) AS unknown_usage_calls,
                  SUM(input_tokens) AS known_input_tokens,SUM(output_tokens) AS known_output_tokens,
                  SUM(total_tokens) AS known_total_tokens,AVG(latency_ms) AS average_latency_ms
                FROM model_call WHERE started_at>=:from AND started_at<:to
                """).param("from",LocalDateTime.ofInstant(from,ZoneOffset.UTC)).param("to",LocalDateTime.ofInstant(to,ZoneOffset.UTC))
                .query(Summary.class).single();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    public record CallView(long id,String invocationId,int attemptNumber,String callType,String route,boolean streaming,
            Long messageId,Long taskId,String requestedModel,String actualModel,String status,Integer inputTokens,Integer outputTokens,
            Integer totalTokens,boolean usageKnown,Long latencyMs,String errorType,LocalDateTime startedAt,LocalDateTime finishedAt) {}
    public record Summary(long attempts,long completed,long failed,long cancelled,long running,long knownUsageCalls,
            long unknownUsageCalls,Long knownInputTokens,Long knownOutputTokens,Long knownTotalTokens,BigDecimal averageLatencyMs) {}
}
