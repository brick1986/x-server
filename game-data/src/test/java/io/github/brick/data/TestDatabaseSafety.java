package io.github.brick.data;

import com.mongodb.ConnectionString;

import java.net.URI;

/**
 * Test-only guard: cleanup must never silently target an application database.
 */
public final class TestDatabaseSafety {
    private TestDatabaseSafety() {
    }

    public static void validate(String redisAddress, int database, String mongoUri, String mongoDb, boolean cleanup) {
        if (!cleanup || database < 1 || database > 15 || !mongoDb.matches("[a-zA-Z0-9_]+_test")
                || !local(URI.create(redisAddress).getHost()))
            throw new IllegalStateException("Explicit disposable local test databases required");
        for (String host : new ConnectionString(mongoUri).getHosts()) {
            String name = host.startsWith("[") ? host.substring(1, host.indexOf(']')) : host.split(":")[0];
            if (!local(name)) throw new IllegalStateException("Mongo test host must be loopback");
        }
    }

    private static boolean local(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }
}
