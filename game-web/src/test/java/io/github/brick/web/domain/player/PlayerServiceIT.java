package io.github.brick.web.domain.player;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.RedissonLockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.*;
import io.github.brick.web.domain.account.*;
import io.github.brick.web.auth.SessionIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class PlayerServiceIT extends LocalRedisMongo {
    PlayerService service(MongoAccountRepository repo) {
        return new PlayerService(new RedissonLockScope(redis, new RedisStore(redis), new MongoStore(mongo, MONGO_DB), new CommitLua(redis), new JsonCodec(), 2000, 10, Map.of("account", -1, "guild", 0, "player", 1)), repo, e -> {
        }, Clock.systemUTC());
    }

    @Test
    void concurrentCreationAndRenameKeepOneUid() throws Exception {
        var repo = new MongoAccountRepository(mongo, MONGO_DB);
        repo.insert(new Account(1, "alice", "hash", 11, false, 100));
        var a = service(repo);
        var b = service(new MongoAccountRepository(mongo, MONGO_DB));
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var x = pool.submit(() -> {
                gate.await();
                return a.ensureInitialized(1, "a");
            });
            var y = pool.submit(() -> {
                gate.await();
                return b.ensureInitialized(1, "b");
            });
            assertThat(x.get(10, TimeUnit.SECONDS).uid()).isEqualTo(11);
            assertThat(y.get(10, TimeUnit.SECONDS).uid()).isEqualTo(11);
        }
        var identity = new SessionIdentity(1, 11, Long.MAX_VALUE, "digest");
        a.rename(identity, "Changed", "req");
        assertThat(b.ensureInitialized(1, "retry").displayName()).isEqualTo("Changed");
        String key = DataKeys.key("player", 11, "profile");
        var cache = new RedisStore(redis);
        new MongoStore(mongo, MONGO_DB).upsert(key, cache.get(key));
        cache.del(key);
        assertThat(a.get(identity).displayName()).isEqualTo("Changed");
        assertThat(cache.get(key)).contains("Changed");
    }

    @Test
    void retryAfterMarkFailurePreservesExistingProfile() {
        var repo = new MongoAccountRepository(mongo, MONGO_DB) {
            boolean fail = true;

            @Override
            public void markPlayerInitialized(long a, long p) {
                if (fail) {
                    fail = false;
                    throw new IllegalStateException("injected");
                }
                super.markPlayerInitialized(a, p);
            }
        };
        repo.insert(new Account(1, "alice", "hash", 11, false, 100));
        var svc = service(repo);
        assertThatThrownBy(() -> svc.ensureInitialized(1, "first")).isInstanceOf(IllegalStateException.class);
        svc.rename(new SessionIdentity(1, 11, Long.MAX_VALUE, "digest"), "Retained", "req");
        assertThat(svc.ensureInitialized(1, "retry").displayName()).isEqualTo("Retained");
        assertThat(repo.findById(1).orElseThrow().playerInitialized()).isTrue();
    }
}
