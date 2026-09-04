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
        try {
            return transaction.execute(status -> {
                var holder = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO model_call(invocation_id,attempt_number,call_type,route,streaming,message_id,requested_model)
                        VALUES (:invocation,:attempt,:type,:route,:streaming,:message,:model)
                        """).param("invocation", invocation).param("attempt", attempt).param("route", route)
                        .param("type",type).param("streaming", streaming).param("message", messageId, Types.BIGINT).param("model", model)
                        .update(holder);
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
                      error_type=:error,finished_at=CURRENT_TIMESTAMP(6)
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
