package io.github.xw66.knowflowai.document;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RequestIdempotency {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration lease;

    public RequestIdempotency(StringRedisTemplate redis, @Value("${app.idempotency.enabled:true}") boolean enabled,
            @Value("${app.idempotency.lease:PT30S}") Duration lease) {
        if (lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("幂等协调租约须为 1 秒至 5 分钟");
        }
        this.redis = redis;
        this.enabled = enabled;
        this.lease = lease;
    }

    public <T> T execute(String scope, Supplier<T> action) {
        if (!enabled) return action.get();
        String key = "knowflow:request:v1:" + scope;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, lease);
        } catch (DataAccessException exception) {
            LoggerFactory.getLogger(getClass()).warn("Redis 幂等协调不可用，使用数据库去重：{}", exception.getClass().getSimpleName());
            return action.get();
        }
        if (Boolean.FALSE.equals(acquired)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同幂等键的请求正在处理，请稍后使用原键重试");
        }
        if (acquired == null) return action.get();
        // ponytail: 不续租，超时重叠仍由数据库行锁和唯一约束去重；并非任务执行租约。
        try {
            return action.get();
        } finally {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        release(key, token);
                    }
                });
            } else {
                release(key, token);
            }
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (DataAccessException exception) {
            LoggerFactory.getLogger(getClass()).warn("幂等协调锁释放失败，等待租约过期：{}", exception.getClass().getSimpleName());
        }
    }
}
