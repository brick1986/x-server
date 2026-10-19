package io.github.brick.web.auth;

import io.github.brick.web.api.GameException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;

import java.io.IOException;

public final class AuthFilter implements Filter {
    private final SessionStore sessions;
    private final LoginRateLimiter limiter;

    public AuthFilter(SessionStore sessions, LoginRateLimiter limiter) {
        this.sessions = sessions;
        this.limiter = limiter;
    }

    @Override
    public void doFilter(ServletRequest raw, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) raw;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        RequestIdentity.clear();
        try {
            // Registered only for /api/*; requestBoundary has rejected noncanonical paths.
            if (path.equals("/api/auth/register") || path.equals("/api/auth/login")) {
                limiter.checkIp(request.getRemoteAddr());
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1)
                throw GameException.unauthorized();
            var identity = sessions.authenticate(header.substring(7));
            if (identity == null) throw GameException.unauthorized();
            RequestIdentity.set(identity);
            MDC.put("accountId", Long.toString(identity.accountId()));
            MDC.put("userId", Long.toString(identity.uid()));
            chain.doFilter(request, response);
        } finally {
            RequestIdentity.clear();
            MDC.remove("accountId");
            MDC.remove("userId");
        }
    }
}
