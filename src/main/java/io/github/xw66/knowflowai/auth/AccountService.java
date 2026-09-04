package io.github.xw66.knowflowai.auth;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService {

    private final JdbcClient jdbcClient;

    public AccountService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Account loadUserByUsername(String username) {
        return jdbcClient.sql("SELECT id, username, password_hash, system_role, status FROM app_user WHERE username = :username")
                .param("username", username.toLowerCase(Locale.ROOT))
                .query((rs, row) -> new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("system_role"), rs.getString("status")))
                .optional().orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    public Account findById(long id) {
        return jdbcClient.sql("SELECT id, username, system_role, status FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new Account(rs.getLong("id"), rs.getString("username"), "",
                        rs.getString("system_role"), rs.getString("status")))
                .optional().orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    public AbstractAuthenticationToken authenticate(Jwt jwt) {
        try {
            // 权限以当前数据库状态为准，旧令牌不能保留已撤销的角色。
            var account = findById(Long.parseLong(jwt.getSubject()));
            if (!account.isEnabled()) {
                throw new DisabledException("账号不可用");
            }
            return UsernamePasswordAuthenticationToken.authenticated(account, null, account.getAuthorities());
        } catch (NumberFormatException | UsernameNotFoundException exception) {
            throw new BadCredentialsException("令牌对应账号无效");
        } catch (DataAccessException exception) {
            throw new AuthenticationServiceException("暂时无法校验账号状态");
        }
    }

    public static class Account extends User {
        private final long id;
        private final String role;

        Account(long id, String username, String passwordHash, String role, String status) {
            super(username, passwordHash, "ACTIVE".equals(status), true, true, true,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            this.id = id;
            this.role = role;
        }

        public AccountView view() {
            return new AccountView(id, getUsername(), role, isEnabled() ? "ACTIVE" : "DISABLED");
        }

        public long id() {
            return id;
        }
    }

    public record AccountView(long id, String username, String role, String status) {
    }
}
