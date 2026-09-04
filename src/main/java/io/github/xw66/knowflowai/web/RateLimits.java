package io.github.xw66.knowflowai.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import io.github.xw66.knowflowai.auth.AuthenticationController;
import io.github.xw66.knowflowai.auth.RegistrationController;
import io.github.xw66.knowflowai.chat.AnswerController;
import io.github.xw66.knowflowai.retrieval.SearchController;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimits implements WebMvcConfigurer, HandlerInterceptor {
    private static final String CHECKED = RateLimits.class.getName();
    // ponytail: 首次请求开启固定窗口，边界可出现两倍瞬时突发；实测需要平滑流量时再改令牌桶。
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
                return 0
            end
            local count = tonumber(current)
            if not count or count < 1 then return -1 end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                ttl = tonumber(ARGV[2])
            end
            if count >= tonumber(ARGV[1]) then return math.max(ttl, 1) end
            redis.call('INCR', KEYS[1])
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long windowMillis;
    private final Map<Bucket, Integer> limits;

    public RateLimits(StringRedisTemplate redis,
            @Value("${app.rate-limit.window:PT60S}") Duration window,
            @Value("${app.rate-limit.register:5}") int register,
            @Value("${app.rate-limit.login:20}") int login,
            @Value("${app.rate-limit.search:60}") int search,
            @Value("${app.rate-limit.answer:20}") int answer) {
        this.redis = redis;
        this.windowMillis = window.toMillis();
        this.limits = Map.of(Bucket.REGISTER, register, Bucket.LOGIN, login, Bucket.SEARCH, search, Bucket.ANSWER, answer);
        if (windowMillis < 1000 || windowMillis > 3600000 || limits.values().stream().anyMatch(value -> value < 1 || value > 1000000)) {
            throw new IllegalArgumentException("限流窗口须为 1 秒至 1 小时，次数须为 1 至 1000000");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || request.getAttribute(CHECKED) != null
                || !"POST".equals(request.getMethod()) || !(handler instanceof HandlerMethod method)) return true;
        Class<?> controller = method.getBeanType();
        Bucket bucket;
        if (controller == RegistrationController.class) bucket = Bucket.REGISTER;
        else if (controller == AuthenticationController.class && method.getMethod().getName().equals("login")) bucket = Bucket.LOGIN;
        else if (controller == SearchController.class) bucket = Bucket.SEARCH;
        else if (controller == AnswerController.class) bucket = Bucket.ANSWER;
        else return true;

        String subject;
        if (bucket == Bucket.REGISTER || bucket == Bucket.LOGIN) {
            // 默认不信任 Forwarded/X-Forwarded-For，来源地址由 Servlet 容器提供。
            subject = request.getRemoteAddr();
        } else {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof Account account)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要有效的登录令牌");
            }
            subject = Long.toString(account.id());
        }
        request.setAttribute(CHECKED, Boolean.TRUE);
        long retryMillis = acquire(bucket, subject);
        if (retryMillis > 0) {
            response.setHeader("Retry-After", Long.toString((retryMillis + 999) / 1000));
            response.setHeader("Cache-Control", "no-store");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        }
        return true;
    }

    public long acquire(Bucket bucket, String subject) {
        try {
            Long result = redis.execute(ACQUIRE, List.of("knowflow:rate:v1:" + bucket.name() + ":" + subject),
                    Integer.toString(limits.get(bucket)), Long.toString(windowMillis));
            if (result != null && result >= 0) return result;
        } catch (DataAccessException exception) {
            LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("bucket", bucket.name())
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName()).log("限流服务不可用，拒绝本次请求");
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂时不可用，请稍后重试");
    }

    public enum Bucket { REGISTER, LOGIN, SEARCH, ANSWER }
}
