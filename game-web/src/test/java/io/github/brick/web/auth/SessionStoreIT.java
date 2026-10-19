package io.github.brick.web.auth;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.web.config.WebProperties;
import io.github.brick.web.api.GameException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.*;

import static org.assertj.core.api.Assertions.*;

class SessionStoreIT extends LocalRedisMongo {
    private SessionStore store(Clock clock) {
        return new SessionStore(redis, new JsonCodec(), new SecureRandom(), clock, new WebProperties());
    }

    @Test
    void replacementAndOldLogoutCannotRevokeNewSession() {
        var sessions = store(Clock.systemUTC());
        var first = sessions.issue(1, 11);
        assertThat(first.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(sessions.authenticate(first.token()).uid()).isEqualTo(11);
        var second = sessions.issue(1, 11);
        assertThatThrownBy(() -> sessions.authenticate(first.token())).isInstanceOf(GameException.class);
        sessions.revoke(first.identity());
        assertThat(sessions.authenticate(second.token()).accountId()).isEqualTo(1);
        sessions.revoke(second.identity());
        assertThatThrownBy(() -> sessions.authenticate(second.token())).isInstanceOf(GameException.class);
    }

    @Test
    void rejectsExpiredForgedAndOrphanedSessions() {
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        var sessions = store(clock);
        var issued = sessions.issue(1, 11);
        assertThatThrownBy(() -> store(Clock.offset(clock, Duration.ofDays(2))).authenticate(issued.token())).isInstanceOf(GameException.class);
        assertThatThrownBy(() -> sessions.authenticate("x".repeat(43))).isInstanceOf(GameException.class);
        redis.getKeys().delete("auth:current:1");
        assertThatThrownBy(() -> sessions.authenticate(issued.token())).isInstanceOf(GameException.class);
        for (String key : redis.getKeys().getKeys()) assertThat(key).doesNotContain(issued.token());
    }
}
