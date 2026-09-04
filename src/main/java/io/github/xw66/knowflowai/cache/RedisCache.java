package io.github.xw66.knowflowai.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RedisCache {

    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public RedisCache(StringRedisTemplate redis, @Value("${app.cache.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public boolean usable() {
        // 事务内可能读到未提交的数据，直接走数据库，不向共享缓存发布。
        return enabled && !TransactionSynchronizationManager.isActualTransactionActive();
    }

    public String get(String key) {
        if (!usable()) return null;
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException exception) {
            log.warn("缓存读取失败，回源数据库：{}", exception.getClass().getSimpleName());
            return null;
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (!usable()) return;
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException exception) {
            log.warn("缓存写入失败：{}", exception.getClass().getSimpleName());
        }
    }

    public void invalidateAfterCommit(String key) {
        if (!enabled) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redis.delete(key);
                } catch (DataAccessException exception) {
                    // 版本隔离保证删除失败或旧请求迟到回填时，新请求仍不会读取旧数据。
                    log.warn("旧缓存失效失败，等待 TTL 清理：{}", exception.getClass().getSimpleName());
                }
            }
        });
    }

}
