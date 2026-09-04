package io.github.xw66.knowflowai.observability;

import java.sql.Types;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelCallLog {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final boolean budgetEnabled;

    public ModelCallLog(JdbcClient jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, false);
    }

    @Autowired
    public ModelCallLog(JdbcClient jdbc, PlatformTransactionManager manager,
            @Value("${app.model-budget.enabled:true}") boolean budgetEnabled) {
        this.jdbc = jdbc;
        this.budgetEnabled = budgetEnabled;
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
                long id=Objects.requireNonNull(holder.getKey()).longValue();
                if (budgetEnabled) reserve(id,type);
                return id;
            });
        } catch (DataAccessException | TransactionException exception) {
            // 不能记录尝试时不发送模型请求，避免出现不可追踪的消耗。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用记录暂时不可用");
        }
    }

    private void reserve(long callId, String type) {
        var price=jdbc.sql("SELECT price_input_per_million,price_output_per_million,price_total_per_million FROM model_call WHERE id=:id")
                .param("id",callId).query((rs,n)->new Price(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getBigDecimal(3))).single();
        BigDecimal reserve=switch(type) {
            case "EMBEDDING" -> price.input==null ? null : price.input.multiply(BigDecimal.valueOf(81920)).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.UP);
            case "RERANK" -> price.total!=null ? price.total.multiply(BigDecimal.valueOf(400000)).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.UP)
                    : price.input==null ? null : price.input.multiply(BigDecimal.valueOf(400000)).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.UP);
            case "CHAT","REWRITE" -> price.input==null ? null : price.input.multiply(BigDecimal.valueOf(1_000_000)).add(
                    (price.output==null?BigDecimal.ZERO:price.output).multiply(BigDecimal.valueOf(393216))).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.UP);
            default -> null;
        };
        if(reserve==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型价格或计费上界未配置");
        int updated=jdbc.sql("""
                UPDATE model_budget SET held_cny=held_cny+:amount
                WHERE id=1 AND halted=FALSE AND spent_cny+held_cny+:amount<=limit_cny
                """).param("amount",reserve).update();
        if(updated!=1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型预算不足或已冻结");
        jdbc.sql("UPDATE model_call SET budget_reserved=:amount WHERE id=:id").param("amount",reserve).param("id",callId).update();
    }

    public void finish(long id, String status, String model, Tokens usage, long latencyMillis, String errorType) {
        try {
            transaction.executeWithoutResult(ignored -> {
                if (budgetEnabled) settle(id, model, usage);
                jdbc.sql("""
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
                    .param("error", errorType, Types.VARCHAR).update();
            });
        } catch (DataAccessException | TransactionException exception) {
            // 不把记账失败转换为模型失败或重试；保留 RUNNING 供查询和后续对账。
            LoggerFactory.getLogger(getClass()).atError().addKeyValue("modelCallId", id)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("模型调用终态记录失败，待对账");
        }
    }

    private void settle(long id, String model, Tokens usage) {
        var row=jdbc.sql("SELECT budget_reserved,budget_settled,requested_model,price_max_input_tokens,price_input_per_million,price_output_per_million,price_total_per_million FROM model_call WHERE id=:id FOR UPDATE")
                .param("id",id).query((rs,n)->new BudgetRow(rs.getBigDecimal(1),rs.getBoolean(2),rs.getString(3),rs.getObject(4,Integer.class),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7))).single();
        if(row.reserved==null || row.settled || usage==null) return;
        if(model!=null && !model.isBlank() && !model.equals(row.requested)) return;
        if(row.maxInput!=null && (usage.input()==null || usage.input()>row.maxInput)) return;
        BigDecimal cost = row.total!=null && usage.total()!=null
                ? row.total.multiply(BigDecimal.valueOf(usage.total())).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.HALF_UP)
                : row.input!=null && usage.input()!=null && (row.output==null || usage.output()!=null)
                    ? row.input.multiply(BigDecimal.valueOf(usage.input())).add((row.output==null?BigDecimal.ZERO:row.output).multiply(BigDecimal.valueOf(usage.output()==null?0:usage.output()))).divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.HALF_UP)
                    : null;
        if(cost==null) return;
        if(cost.compareTo(row.reserved)>0) {
            jdbc.sql("UPDATE model_budget SET halted=TRUE WHERE id=1").update();
            return;
        }
        jdbc.sql("UPDATE model_budget SET held_cny=held_cny-:reserved,spent_cny=spent_cny+:cost WHERE id=1")
                .param("reserved",row.reserved).param("cost",cost).update();
        jdbc.sql("UPDATE model_call SET budget_settled=TRUE WHERE id=:id").param("id",id).update();
    }

    private record Price(BigDecimal input,BigDecimal output,BigDecimal total) {}
    private record BudgetRow(BigDecimal reserved,boolean settled,String requested,Integer maxInput,BigDecimal input,BigDecimal output,BigDecimal total) {}

    public record Tokens(Integer input, Integer output, Integer total) {}
}
