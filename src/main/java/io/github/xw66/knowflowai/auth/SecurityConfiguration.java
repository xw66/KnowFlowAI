package io.github.xw66.knowflowai.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String encodedKey) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET 须为 Base64 编码的随机密钥");
        }
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET 解码后须至少为 32 字节");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtTimestampValidator(Duration.ZERO),
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience)),
                new JwtClaimValidator<Instant>("exp", Objects::nonNull),
                new JwtClaimValidator<String>("sub", subject -> {
                    try {
                        return Long.parseLong(subject) > 0;
                    } catch (NumberFormatException exception) {
                        return false;
                    }
                })));
        return decoder;
    }

    @Bean
    AuthenticationManager authenticationManager(AccountService accounts, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            var status = exception instanceof AuthenticationServiceException ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED;
            if (status == HttpStatus.UNAUTHORIZED) {
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            writeProblem(mapper, request, response, status,
                    status == HttpStatus.UNAUTHORIZED ? "需要有效的登录令牌" : "认证服务暂时不可用");
        };
        AccessDeniedHandler forbidden = (request, response, exception) ->
                writeProblem(mapper, request, response, HttpStatus.FORBIDDEN, "没有访问该资源的权限");
        var failureHandler = new AuthenticationEntryPointFailureHandler(unauthorized);
        // 数据库故障也通过统一入口返回 503，避免过滤器把服务异常继续抛成 500。
        failureHandler.setRethrowAuthenticationServiceException(false);
        return http
                // 仅接受显式 Bearer 请求头，不使用 Cookie 身份认证。
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(accounts::authenticate))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        }))
                .build();
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
