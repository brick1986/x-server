package io.github.brick.web.api;

import io.github.brick.contract.game.*;
import io.github.brick.web.auth.*;
import io.github.brick.web.codec.ProfileMapper;
import org.springframework.web.bind.annotation.*;
import org.slf4j.MDC;

@RestController
@RequestMapping(value = "/api/auth", produces = "application/x-protobuf")
public class AuthController {
    private final AuthService auth;
    private final SessionStore sessions;
    private final LoginRateLimiter limiter;

    public AuthController(AuthService auth, SessionStore sessions, LoginRateLimiter limiter) {
        this.auth = auth;
        this.sessions = sessions;
        this.limiter = limiter;
    }

    @PostMapping(value = "/register", consumes = "application/x-protobuf")
    public RegisterResponse register(@RequestBody RegisterRequest request) {
        String name = CredentialRules.normalizeUsername(request.getUsername());
        limiter.checkAccount(name);
        return RegisterResponse.newBuilder().setAccountId(auth.register(name, request.getPassword()).accountId()).build();
    }

    @PostMapping(value = "/login", consumes = "application/x-protobuf")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String name = CredentialRules.normalizeUsername(request.getUsername());
        limiter.checkAccount(name);
        var result = auth.login(name, request.getPassword(), MDC.get("requestId"));
        return LoginResponse.newBuilder().setOk(true).setUserId(result.profile().uid()).setAccountId(result.profile().accountId())
                .setToken(result.session().token()).setExpiresAt(result.session().identity().expiresAt()).setProfile(ProfileMapper.toMessage(result.profile())).build();
    }

    @PostMapping(value = "/logout", consumes = "application/x-protobuf")
    public LogoutResponse logout(@RequestBody(required = false) LogoutRequest ignored) {
        sessions.revoke(RequestIdentity.require());
        return LogoutResponse.newBuilder().setOk(true).build();
    }
}
