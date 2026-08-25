package io.github.brick.data.lock;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockCtxIT extends LocalRedisMongo {

    private static final String PROFILE = DataKeys.key("player", 1, "profile");

    private record Profile(String name, int coin) {}

    /** 直接构造持单锁的 LockCtx（绕过 LockScope，便于聚焦 ctx 行为）。统一走 Map 构造器。 */
    private LockCtx singleLockCtx(String entity, long id) {
        RLock lock = redis.getLock(DataKeys.lockKey(entity, id));
        lock.lock(10, java.util.concurrent.TimeUnit.SECONDS);
        return new RedissonLockCtx(
                Map.of(DataKeys.lockKey(entity, id), lock), List.of(lock),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
    }

    // §7 用例 1：不存在无锁回填路径（空锁 Map → resolveLock 返回 null → 抛 LockLostException，不 SET）
    @Test
    void getWithoutLockThrowsAndDoesNotSet() {
        LockCtx ctx = new RedissonLockCtx(
                Map.of(), List.of(),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());   // 空锁 Map
        assertThatThrownBy(() -> ctx.get(PROFILE, Profile.class))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();   // 没回填
    }

    // §7 用例 2：写路径 miss 加载（get miss 从 Mongo 加载并回填）
    @Test
    void getMissLoadsFromMongoAndBackfills() {
        new MongoStore(mongo, MONGO_DB).upsert(PROFILE, "{\"name\":\"alice\",\"coin\":100}");
        try (LockCtx ctx = singleLockCtx("player", 1)) {
            Profile p = ctx.get(PROFILE, Profile.class);
            assertThat(p).isEqualTo(new Profile("alice", 100));
            // 回填后 Redis 命中
            assertThat(new RedisStore(redis).get(PROFILE)).contains("\"name\":\"alice\"");
            assertThat(ctx.get(PROFILE, Profile.class).coin()).isEqualTo(100);
        }
    }

    @Test
    void putCommitsUnderLock() {
        try (LockCtx ctx = singleLockCtx("player", 1)) {
            ctx.put(PROFILE, new Profile("bob", 50));
        }
        assertThat(new RedisStore(redis).get(PROFILE)).contains("\"name\":\"bob\"");
        assertThat(new DirtyLedger(redis).members()).contains(PROFILE);
    }

    // §7 用例 3：提交门控失锁绝不写（mock RLock isHeld=false）
    @Test
    void putWhenLockLostThrowsAndRedisUnchanged() {
        RLock mockLock = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(mockLock.isHeldByCurrentThread()).thenReturn(false);
        LockCtx ctx = new RedissonLockCtx(
                Map.of(DataKeys.lockKey("player", 1), mockLock), List.of(mockLock),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.put(PROFILE, new Profile("x", 1)))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();
        assertThat(new DirtyLedger(redis).members()).doesNotContain(PROFILE);
    }

    // §7 用例 10：resolveLock 命中本实体锁（player:1 失锁而 player:2 仍持，对 player:1 key 操作命中 player:1 锁）
    @Test
    void resolveLockHitsCorrectEntityLock() {
        RLock l1 = org.mockito.Mockito.mock(RLock.class);
        RLock l2 = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(l1.isHeldByCurrentThread()).thenReturn(false);
        org.mockito.Mockito.when(l2.isHeldByCurrentThread()).thenReturn(true);
        Map<String, RLock> byName = Map.of(
                DataKeys.lockKey("player", 1), l1,
                DataKeys.lockKey("player", 2), l2);
        LockCtx ctx = new RedissonLockCtx(byName, List.of(l1, l2),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.get(DataKeys.key("player", 1, "profile"), Profile.class))
                .isInstanceOf(LockLostException.class);   // 命中 player:1 锁而非 player:2
    }

    // §7 用例 11（单实体）：失锁那次未写、无部分提交，可安全重试
    @Test
    void singleEntityPutLostLockNoPartialCommit() {
        RLock mockLock = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(mockLock.isHeldByCurrentThread()).thenReturn(false);
        LockCtx ctx = new RedissonLockCtx(
                Map.of(DataKeys.lockKey("player", 1), mockLock), List.of(mockLock),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.put(PROFILE, new Profile("x", 1)))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();
        assertThat(new DirtyLedger(redis).members()).doesNotContain(PROFILE);
    }
}
