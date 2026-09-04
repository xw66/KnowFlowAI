package io.github.xw66.knowflowai.observability;

import java.sql.Types;
import java.util.Objects;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelCallLog {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public ModelCallLog(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long start(String invocation, int attempt, String route, boolean streaming, Long messageId, String model) {
        return start(invocation,attempt,"CHAT",route,streaming,messageId,model);
    }

    public long start(String invocation, int attempt, String type, String route, boolean streaming, Long messageId, String model) {
        return start(invocation,attempt,type,route,streaming,messageId,model,null);
    }

    public long start(String invocation, int attempt, String type, String route, boolean streaming, Long messageId, String model, Long taskId) {
        return start(invocation,attempt,type,route,streaming,messageId,model,taskId,null);
    }

    public long start(String invocation, int attempt, String type, String route, boolean streaming, Long messageId, String model, Long taskId, String endpoint) {
        try {
            return transaction.execute(status -> {
                var holder = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO model_call(invocation_id,attempt_number,call_type,route,streaming,message_id,requested_model,task_id,
                          price_version,price_currency,price_input_per_million,price_output_per_million,price_total_per_million,
                          price_max_input_tokens,price_verified_at,price_source_url)
                        SELECT :invocation,:attempt,:type,:route,:streaming,:message,:model,:task,
                          p.version,p.currency,p.input_per_million,p.output_per_million,p.total_per_million,
                          p.max_input_tokens,p.verified_at,p.source_url
                        FROM (SELECT 1) seed LEFT JOIN model_price p ON p.call_type=:priceType AND p.endpoint=:endpoint
                          AND p.model=:model AND p.verified_at<=CURRENT_DATE
                        ORDER BY p.verified_at DESC,p.id DESC LIMIT 1
                        """).param("invocation", invocation).param("attempt", attempt).param("route", route)
                        .param("type",type).param("streaming", streaming).param("message", messageId, Types.BIGINT).param("model", model)
                        .param("task",taskId,Types.BIGINT).param("priceType",type.equals("REWRITE")?"CHAT":type)
                        .param("endpoint",endpoint==null?null:endpoint.replaceAll("/+$",""),Types.VARCHAR).update(holder);
                return Objects.requireNonNull(holder.getKey()).longValue();
            });
        } catch (DataAccessException | TransactionException exception) {
            // 不能记录尝试时不发送模型请求，避免出现不可追踪的消耗。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用记录暂时不可用");
        }
    }

    public void finish(long id, String status, String model, Tokens usage, long latencyMillis, String errorType) {
        try {
            transaction.executeWithoutResult(ignored -> jdbc.sql("""
                    UPDATE model_call SET status=:status,actual_model=:model,input_tokens=:input,
                      output_tokens=:output,total_tokens=:total,usage_known=:known,latency_ms=:latency,
                      error_type=:error,finished_at=CURRENT_TIMESTAMP(6),
                      estimated_cost=CASE
                        WHEN price_version IS NULL THEN NULL
                        WHEN :model IS NOT NULL AND :model<>'' AND BINARY :model<>BINARY requested_model THEN NULL
                        WHEN price_max_input_tokens IS NOT NULL AND (:input IS NULL OR :input>price_max_input_tokens) THEN NULL
                        WHEN price_total_per_million IS NOT NULL AND :total IS NOT NULL
                          THEN CAST(:total*price_total_per_million AS DECIMAL(30,12))/1000000
                        WHEN price_input_per_million IS NOT NULL AND :input IS NOT NULL
                          AND (price_output_per_million=0 OR :output IS NOT NULL)
                          THEN CAST(:input*price_input_per_million+COALESCE(:output,0)*price_output_per_million AS DECIMAL(30,12))/1000000
                        ELSE NULL END
                    WHERE id=:id AND status='RUNNING'
                    """).param("id", id).param("status", status).param("model", model, Types.VARCHAR)
                    .param("input", usage == null ? null : usage.input(), Types.INTEGER)
                    .param("output", usage == null ? null : usage.output(), Types.INTEGER)
                    .param("total", usage == null ? null : usage.total(), Types.INTEGER)
                    .param("known", usage != null).param("latency", latencyMillis)
                    .param("error", errorType, Types.VARCHAR).update());
        } catch (DataAccessException | TransactionException exception) {
            // 不把记账失败转换为模型失败或重试；保留 RUNNING 供查询和后续对账。
            LoggerFactory.getLogger(getClass()).atError().addKeyValue("modelCallId", id)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("模型调用终态记录失败，待对账");
        }
    }

    public record Tokens(Integer input, Integer output, Integer total) {}
}
