package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlushMetricsIT extends LocalRedisMongo {

    private static final String PROFILE_1 = DataKeys.key("player", 1, "profile");
    private static final String PROFILE_2 = DataKeys.key("player", 2, "profile");
    private static final String BAG_2 = DataKeys.key("player", 2, "bag");

    private MeterRegistry registry;
    private DirtyLedger dirty;

    private FlushOrchestrator orchestrator(int chunkSize) {
        registry = new SimpleMeterRegistry();
        dirty = new DirtyLedger(redis);
        return new FlushOrchestrator(redis, dirty, new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), chunkSize, 60L,
                new FlushMetrics(registry, dirty));
    }

    @Test
    void backlogGaugeReflectsDirtySize() {
        // 最该看的指标：持续增长说明落盘跟不上写入
        FlushOrchestrator o = orchestrator(500);
        dirty.mark(PROFILE_1);
        dirty.mark(BAG_2);
        assertThat(registry.get("dbserver.dirty.backlog").gauge().value()).isEqualTo(2.0);

        new RedisStore(redis).set(PROFILE_1, "{}");
        new RedisStore(redis).set(BAG_2, "[]");
        o.flushOnce();
        assertThat(registry.get("dbserver.dirty.backlog").gauge().value()).isZero();
    }

    @Test
    void roundRecordsTimerAndFlushedCount() {
        RedisStore r = new RedisStore(redis);
        FlushOrchestrator o = orchestrator(500);
        r.set(PROFILE_1, "{}");
        dirty.mark(PROFILE_1);

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.round").timer().count()).isEqualTo(1);
        assertThat(registry.get("dbserver.flush.keys").counter().count()).isEqualTo(1.0);
    }

    @Test
    void missingKeysAreCountedSeparatelyFromFlushedOnes() {
        FlushOrchestrator o = orchestrator(500);
        dirty.mark(DataKeys.key("player", 404, "bag"));          // Redis 里没有

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.missing").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dbserver.flush.keys").counter().count()).isZero();
    }

    @Test
    void failedKeysAreCounted() {
        db().getCollection("player:profile").createIndex(
                new org.bson.Document("v", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
        FlushOrchestrator o = orchestrator(500);
        new MongoStore(mongo, MONGO_DB).upsert(PROFILE_1, "{\"dup\":1}");
        RedisStore r = new RedisStore(redis);
        r.set(PROFILE_2, "{\"dup\":1}");
        dirty.mark(PROFILE_2);

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void skippedRoundIsCountedWhenAnotherInstanceHoldsTheLock() {
        FlushOrchestrator o = orchestrator(500);
        RedissonClient other = newClient();
        try {
            RLock held = other.getLock(FlushOrchestrator.FLUSH_LOCK);
            assertThat(held.tryLock(0, 60, TimeUnit.SECONDS)).isTrue();
            try {
                o.flushOnce();
                assertThat(registry.get("dbserver.flush.skipped").counter().count()).isEqualTo(1.0);
                assertThat(registry.get("dbserver.flush.round").timer().count()).isZero();
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
}
