package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FlushLockIT extends LocalRedisMongo {

    private FlushOrchestrator orchestrator() {
        return new FlushOrchestrator(redis, new DirtyLedger(redis), new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), 500, 60L);
    }

    @Test
    void lockNameIsProcessLevelNotAnEntityLock() {
        // 刻意不走 DataKeys.lockKey()——那是 lock:{entity}:{id} 的实体锁命名。
        // 本锁是进程级互斥锁，不复用以免两种语义混淆。
        assertThat(FlushOrchestrator.FLUSH_LOCK).isEqualTo("lock:dbserver:flush");
    }

    @Test
    void roundSkipsWhenAnotherInstanceHoldsTheLock() {
        // 落盘 spec §5 的回归测试：验证第二实例跳过，且**零标记丢失**
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{\"v\":1}");
        d.mark("player:1:profile");

        RedissonClient other = newClient();           // 模拟另一个 dbserver 实例
        try {
            RLock held = other.getLock(FlushOrchestrator.FLUSH_LOCK);
            assertThat(held.tryLock(0, 60, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(orchestrator().flushOnce()).isEqualTo(FlushOrchestrator.INTERRUPTED);
                // 关键：本轮什么都没做，dirty 一个不少（而不是被排空后丢掉）
                assertThat(d.members()).containsExactly("player:1:profile");
                assertThat(d.inflightMembers()).isEmpty();
            } finally {
                held.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            other.shutdown();
        }
    }

    @Test
    void roundProceedsOnceTheLockIsFree() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{\"v\":1}");
        d.mark("player:1:profile");

        assertThat(orchestrator().flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:profile")).isEqualTo("{\"v\":1}");
    }

    @Test
    void lockIsReleasedAfterASuccessfulRoundSoTheNextOneCanRun() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{}");
        d.mark("player:1:profile");
        FlushOrchestrator o = orchestrator();
        o.flushOnce();

        // 锁必须已释放：否则下一轮永远抢不到，落盘就此停摆
        assertThat(redis.getLock(FlushOrchestrator.FLUSH_LOCK).isLocked()).isFalse();
        r.set("player:2:bag", "[]");
        d.mark("player:2:bag");
        assertThat(o.flushOnce()).isEqualTo(1);
    }

    @Test
    void lockIsReleasedEvenWhenTheRoundBlowsUp() {
        // Mongo 连接级异常会穿透 flushOnce（落盘 spec §4），锁仍须释放，
        // 否则一次 Mongo 抖动会让落盘停摆到租约到期。
        // 用覆盖 flushChunk 抛异常的子类，确定性地制造「一轮中途爆炸」，
        // 不依赖某版 Mongo 驱动对非法库名的校验行为。
        DirtyLedger d = new DirtyLedger(redis);
        new RedisStore(redis).set("player:1:profile", "{}");
        d.mark("player:1:profile");

        FlushOrchestrator broken = new FlushOrchestrator(
                redis, d, new RedisStore(redis), new MongoStore(mongo, MONGO_DB), 500, 60L) {
            @Override
            protected void flushChunk(java.util.List<String> chunk) {
                throw new IllegalStateException("mongo 抖了");
            }
        };

        assertThat(catchThrowable(broken::flushOnce)).isNotNull();
        assertThat(redis.getLock(FlushOrchestrator.FLUSH_LOCK).isLocked()).isFalse();
        // 剩余 key 留在 inflight，等下轮 drain 自愈
        assertThat(d.inflightMembers()).containsExactly("player:1:profile");
    }

    @Test
    void multiChunkRoundIsNotMistakenForALostLock() {
        // 每片之后都会检查持锁；正常路径下多片必须完整跑完，不能被误判成中途失锁
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 6; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }
        FlushOrchestrator perKeyChunks = new FlushOrchestrator(
                redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L);

        assertThat(perKeyChunks.flushOnce()).isEqualTo(6);
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void losingLockMidRoundInterruptsAndLeavesRemainderInInflight() {
        // 落盘 spec §5：每片处理完检查持锁，失锁立即中断本轮、剩余留在 in-flight。
        // 覆盖 stillHoldsLock 让「第一片之后失锁」确定性发生。
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 4; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }

        FlushOrchestrator losesLock = new FlushOrchestrator(
                redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L) {
            private int chunksDone = 0;

            @Override
            protected boolean stillHoldsLock(org.redisson.api.RLock lock) {
                return ++chunksDone < 1;      // 第一片之后即视为失锁
            }
        };

        assertThat(losesLock.flushOnce()).isEqualTo(FlushOrchestrator.INTERRUPTED);
        // 第一片已落，剩下三个仍在 in-flight（没被 ack、也没丢）
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:0:profile")).isNotNull();
        assertThat(d.inflightMembers()).hasSize(3);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void remainderLeftByAnInterruptedRoundIsPickedUpByTheNextOne() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 3; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }
        new FlushOrchestrator(redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L) {
            @Override
            protected boolean stillHoldsLock(org.redisson.api.RLock lock) {
                return false;                 // 第一片之后就中断
            }
        }.flushOnce();
        assertThat(d.inflightMembers()).hasSize(2);

        // 下一轮（正常持锁）必须把残留捡起来落完
        assertThat(orchestrator().flushOnce()).isEqualTo(2);
        MongoStore m = new MongoStore(mongo, MONGO_DB);
        for (int i = 0; i < 3; i++) {
            assertThat(m.load("player:" + i + ":profile")).isEqualTo("{\"i\":" + i + "}");
        }
        assertThat(d.inflightMembers()).isEmpty();
    }
}
