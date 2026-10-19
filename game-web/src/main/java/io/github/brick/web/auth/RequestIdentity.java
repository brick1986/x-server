package io.github.brick.web.auth;

import io.github.brick.web.api.GameException;

public final class RequestIdentity {
    private static final ThreadLocal<SessionIdentity> CURRENT = new ThreadLocal<>();

    private RequestIdentity() {
    }

    public static SessionIdentity require() {
        var identity = CURRENT.get();
        if (identity == null) throw GameException.unauthorized();
        return identity;
    }

    static void set(SessionIdentity identity) {
        CURRENT.set(identity);
    }

    static void clear() {
        CURRENT.remove();
    }
}
