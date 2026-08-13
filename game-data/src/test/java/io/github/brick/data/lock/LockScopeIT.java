package io.github.brick.data.lock;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockScopeIT extends LocalRedisMongo {

    private static final Map<String, Integer> PRIORITY = Map.of("guild", 0, "player", 1);

    private LockScope scope() {
        return new RedissonLockScope(redis, new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec(), 1000L, 10L, PRIORITY);
    }

    // §7 用例 5：跨实体加锁顺序——传入乱序 [player, guild] 实际按 guild>player
    @Test
    void sortReqsOrdersByPriorityThenId() {
        List<LockReq> reqs = List.of(
                LockReq.of("player", 3), LockReq.of("guild", 9),
                LockReq.of("player", 1), LockReq.of("guild", 2));
        List<LockReq> sorted = RedissonLockScope.sortReqs(reqs, PRIORITY);
        assertThat(sorted).containsExactly(
                LockReq.of("guild", 2), LockReq.of("guild", 9),
                LockReq.of("player", 1), LockReq.of("player", 3));
    }

    @Test
    void sortReqsRejectsUnknownEntity() {
        assertThatThrownBy(() -> RedissonLockScope.sortReqs(List.of(LockReq.of("auction", 1)), PRIORITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auction");
    }

    @Test
    void lockAllAcquiresAllAndReleasesOnClose() {
        try (LockCtx ctx = scope().lockAll(List.of(LockReq.of("guild", 7), LockReq.of("player", 1)))) {
            assertThat(redis.getLock(DataKeys.lockKey("guild", 7)).isLocked()).isTrue();
            assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isTrue();
        }
        assertThat(redis.getLock(DataKeys.lockKey("guild", 7)).isLocked()).isFalse();
        assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isFalse();
    }

    // §7 用例 6：all-or-nothing——另一客户端长租占住排序后的**第二把**锁（player:1，priority 1），
    // 使 lockAll 先成功 acquire guild:7，再尝试 player:1 失败 → 触发 rollback 释放已 acquired 的 guild:7。
    // 断言：抛 LockAcquireException + guild:7 已被回滚释放（blocker 持 player:1 而非 guild:7）。
    @Test
    void lockAllRollsBackOnSecondFailure() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://127.0.0.1:6379")
                .setConnectionPoolSize(4).setConnectionMinimumIdleSize(1);
        String pwd = System.getenv("REDIS_TEST_PASSWORD");
        if (pwd != null && !pwd.isEmpty()) {
            cfg.setPassword(pwd);
        }
        RedissonClient blocker = Redisson.create(cfg);
        try {
            RLock held = blocker.getLock(DataKeys.lockKey("player", 1));
            held.lock(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                // 乱序传入，内部排序成 guild:7(优先级 0) → player:1(优先级 1)
                assertThatThrownBy(() -> scope().lockAll(List.of(
                        LockReq.of("player", 1), LockReq.of("guild", 7))))
                        .isInstanceOf(LockAcquireException.class);
                // guild:7 已被回滚释放（lockAll 先成功 acquire、失败后 rollback 释放）
                assertThat(redis.getLock(DataKeys.lockKey("guild", 7)).isLocked()).isFalse();
                // player:1 仍被 blocker 持有（lockAll 未尝试到，或尝试失败未占用）
                assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isTrue();
            } finally {
                held.unlock();
            }
        } finally {
            blocker.shutdown();
        }
    }

    // §7 用例 7：try-with-resources 块内抛异常 → 锁已释放（不等 TTL）
    @Test
    void closeReleasesLocksOnException() {
        try {
            try (LockCtx ctx = scope().lockAll(List.of(LockReq.of("player", 1)))) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException ignored) {
        }
        assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isFalse();
    }
}
