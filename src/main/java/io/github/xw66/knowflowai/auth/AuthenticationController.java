package io.github.xw66.knowflowai.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final AccountService accounts;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;

    public AuthenticationController(AuthenticationManager authenticationManager, JwtEncoder jwtEncoder,
            AccountService accounts, @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience,
            @Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl) {
        if (accessTokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("JWT 有效期须至少为 1 秒");
        }
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.accounts = accounts;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = accessTokenTtl;
    }

    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody CredentialsRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        var account = (AccountService.Account) authentication.getPrincipal();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(Long.toString(account.id())).issuedAt(now).expiresAt(now.plus(accessTokenTtl)).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        var token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
        return new TokenResponse(token.getTokenValue(), "Bearer", accessTokenTtl.toSeconds());
    }

    @GetMapping("/api/auth/me")
    public AccountService.AccountView me(@AuthenticationPrincipal AccountService.Account account) {
        return account.view();
    }

    @GetMapping("/api/admin/users/{id}")
    public AccountService.AccountView user(@PathVariable long id) {
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户 ID 必须为正数");
        }
        try {
            return accounts.findById(id).view();
        } catch (UsernameNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[accessToken=已隐藏]";
        }
    }
}
