package io.github.xw66.knowflowai.auth;

import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationService {

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(JdbcClient jdbcClient, PasswordEncoder passwordEncoder) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisteredUser register(String username, String password) {
        var normalizedUsername = username.toLowerCase(Locale.ROOT);
        var passwordHash = passwordEncoder.encode(password);
        var keyHolder = new GeneratedKeyHolder();
        try {
            // 由唯一索引处理并发注册，角色和账号状态只采用数据库默认值。
            jdbcClient.sql("INSERT INTO app_user (username, password_hash) VALUES (:username, :passwordHash)")
                    .param("username", normalizedUsername)
                    .param("passwordHash", passwordHash)
                    .update(keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        return new RegisteredUser(Objects.requireNonNull(keyHolder.getKey()).longValue(), normalizedUsername, "USER");
    }

    public record RegisteredUser(long id, String username, String role) {
    }
}
