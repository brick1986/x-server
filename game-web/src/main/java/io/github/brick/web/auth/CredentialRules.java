package io.github.brick.web.auth;

import io.github.brick.web.api.GameException;

import java.util.Locale;

public final class CredentialRules {
    private CredentialRules() {
    }

    public static String normalizeUsername(String name) {
        if (name == null) throw GameException.invalid();
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]{3,32}")) throw GameException.invalid();
        return normalized;
    }

    public static void validatePassword(String password) {
        if (password == null) throw GameException.invalid();
        int size = password.codePointCount(0, password.length());
        if (size < 15 || size > 128) throw GameException.invalid();
    }
}
