package io.github.brick.web.domain.player;

import io.github.brick.data.lock.*;
import io.github.brick.data.store.DataKeys;
import io.github.brick.web.domain.account.*;
import io.github.brick.web.auth.SessionIdentity;
import io.github.brick.web.api.GameException;
import io.github.brick.web.log.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerServiceTest {
    final String key = DataKeys.key("player", 11, "profile");
    final SessionIdentity identity = new SessionIdentity(1, 11, Long.MAX_VALUE, "digest");
    final MongoAccountRepository accounts = mock(MongoAccountRepository.class);
    final List<String> calls = new ArrayList<>();
    final Map<String, Object> state = new HashMap<>();
    final LockCtx ctx = new LockCtx() {
        public <T> T get(String k, Class<T> type) {
            return type.cast(state.get(k));
        }

        public <T> void put(String k, T value) {
            calls.add("put");
            state.put(k, value);
        }

        public void close() {
        }
    };

    PlayerService service(BizLogger logger) {
        return new PlayerService(reqs -> ctx, accounts, logger, Clock.fixed(Instant.ofEpochMilli(200), ZoneOffset.UTC));
    }

    @Test
    void logFailurePreventsMutationAndSuccessfulLogPrecedesWrite() {
        when(accounts.findById(1)).thenReturn(Optional.of(new Account(1, "alice", "hash", 11, false, 100)));
        assertThatThrownBy(() -> service(event -> {
            throw new GameException(503, "LOG", "unavailable");
        }).ensureInitialized(1, "req")).isInstanceOf(GameException.class);
        assertThat(state).isEmpty();
        assertThat(calls).isEmpty();
        var profile = service(event -> calls.add("log")).ensureInitialized(1, "req");
        assertThat(calls).containsExactly("log", "put");
        assertThat(profile.createdAt()).isEqualTo(100);
    }

    @Test
    void missingInitializedProfileNeverRecreatesAndOwnershipIsEnforced() {
        when(accounts.findById(1)).thenReturn(Optional.of(new Account(1, "alice", "hash", 11, true, 100)));
        assertThatThrownBy(() -> service(event -> {
        }).ensureInitialized(1, "req")).isInstanceOfSatisfying(GameException.class, e -> assertThat(e.code()).isEqualTo("DATA_INTEGRITY"));
        state.put(key, new PlayerProfile(11, 2, "Other", 100, 100));
        assertThatThrownBy(() -> service(event -> {
        }).get(identity)).isInstanceOfSatisfying(GameException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void sameNameIsNoOpAndNamesUseCodePoints() {
        state.put(key, new PlayerProfile(11, 1, "Alice", 100, 100));
        var svc = service(event -> calls.add("log"));
        assertThat(svc.rename(identity, " Alice ", "req").updatedAt()).isEqualTo(100);
        assertThat(calls).isEmpty();
        assertThat(svc.rename(identity, "😀".repeat(24), "req").displayName()).isEqualTo("😀".repeat(24));
        for (String name : new String[]{"", "x\ny", "😀".repeat(25), "x\u0000y"})
            assertThatThrownBy(() -> svc.rename(identity, name, "req")).isInstanceOf(GameException.class);
    }
}
