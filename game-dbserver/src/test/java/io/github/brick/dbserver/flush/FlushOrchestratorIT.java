package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlushOrchestratorIT extends LocalRedisMongo {

    private static final String PROFILE_1 = DataKeys.key("player", 1, "profile");
    private static final String PROFILE_2 = DataKeys.key("player", 2, "profile");
    private static final String PROFILE_3 = DataKeys.key("player", 3, "profile");
    private static final String BAG_1 = DataKeys.key("player", 1, "bag");
    private static final String BAG_2 = DataKeys.key("player", 2, "bag");
    private static final String BAG_404 = DataKeys.key("player", 404, "bag");

    private DirtyLedger dirty() {
        return new DirtyLedger(redis);
    }

    private FlushOrchestrator orchestrator(int chunkSize) {
        DirtyLedger dirty = new DirtyLedger(redis);
        return new FlushOrchestrator(
                redis, dirty, new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), chunkSize, 60L,
                new FlushMetrics(new SimpleMeterRegistry(), dirty));
    }

    @Test
    void emptyDirtyReturnsZeroSoShutdownLoopCanTellItIsClean() {
        assertThat(orchestrator(500).flushOnce()).isZero();
    }

    @Test
    void flushMovesRedisJsonIntoMongoByteForByte() {
        // 架构 §4.1「落盘零转换」：Mongo 文档的 v 字段必须与 Redis 里的 JSON 逐字节相同
        RedisStore r = new RedisStore(redis);
        String json = "{\"name\":\"alice\",\"coin\":70}";
        r.set(PROFILE_1, json);
        dirty().mark(PROFILE_1);

        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load(PROFILE_1)).isEqualTo(json);
    }

    @Test
    void flushClearsBothDirtyAndInflightOnSuccess() {
        new RedisStore(redis).set(PROFILE_1, "{}");
        DirtyLedger d = dirty();
        d.mark(PROFILE_1);

        orchestrator(500).flushOnce();

        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).isEmpty();   // 每片 ack 后 inflight 自然空
    }

    @Test
    void flushSpansMultipleChunksAndLandsEverything() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        for (int i = 0; i < 12; i++) {
            r.set(DataKeys.key("player", i, "profile"), "{\"i\":" + i + "}");
            d.mark(DataKeys.key("player", i, "profile"));
        }

        assertThat(orchestrator(5).flushOnce()).isEqualTo(12);   // 5+5+2 三片

        MongoStore m = new MongoStore(mongo, MONGO_DB);
        for (int i = 0; i < 12; i++) {
            assertThat(m.load(DataKeys.key("player", i, "profile"))).isEqualTo("{\"i\":" + i + "}");
        }
        assertThat(d.members()).isEmpty();
    }

    @Test
    void keyMissingFromRedisIsSkippedAndNotPutBackIntoDirty() {
        // 落盘 spec §3：key 已被删/过期但标记尚存 → 跳过 upsert，且绝不回 dirty
        // （回了就是永久重试的死循环，每轮都失败、每轮都告警）
        DirtyLedger d = dirty();
        d.mark(BAG_404);

        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);   // 排空了 1 个
        assertThat(d.members()).isEmpty();                       // 但没回来
        assertThat(d.inflightMembers()).isEmpty();
        assertThat(new MongoStore(mongo, MONGO_DB).load(BAG_404)).isNull();
    }

    @Test
    void concurrentWriteDuringFlushSurvivesAsNewDirtyMark() {
        // 落盘 spec §2.1 的端到端回归：被修订掉的「GET 后 SREM」会在这里丢掉 v2 的标记
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set(BAG_1, "[1]");
        d.mark(BAG_1);

        Set<String> snapshot = d.drainAll().values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());   // 模拟本轮已排空
        r.set(BAG_1, "[1,2]");                             // 业务侧写 v2
        d.mark(BAG_1);                                     // 并重新标脏
        d.ackInflight(snapshot);                           // 本轮 ack 自己的快照

        assertThat(d.members()).containsExactly(BAG_1);   // v2 的标记还在

        orchestrator(500).flushOnce();                     // 下一轮落 v2
        assertThat(new MongoStore(mongo, MONGO_DB).load(BAG_1)).isEqualTo("[1,2]");
    }

    @Test
    void leftoverInflightFromCrashedRoundIsRecoveredAndFlushed() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set(PROFILE_1, "{\"v\":1}");
        d.mark(PROFILE_1);
        d.drainAll();                                     // 排空后「崩溃」，不 ack
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).containsExactly(PROFILE_1);

        // 下一轮必须自愈：drain 的恢复步骤把残留合回，照常落盘
        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load(PROFILE_1)).isEqualTo("{\"v\":1}");
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void failedKeyGoesBackToDirtyWhileTheRestOfTheChunkLands() {
        // 落盘 spec §4：唯一索引冲突制造「同批恰好一条写失败」，
        // 验证只有它回 dirty、同片其余照落（unordered 的收益）
        db().getCollection("player:profile").createIndex(
                new org.bson.Document("v", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
        MongoStore m = new MongoStore(mongo, MONGO_DB);
        m.upsert(PROFILE_1, "{\"dup\":1}");

        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set(PROFILE_2, "{\"dup\":1}");    // 与 player:1 的 v 冲突
        r.set(PROFILE_3, "{\"ok\":3}");
        d.mark(PROFILE_2);
        d.mark(PROFILE_3);

        orchestrator(500).flushOnce();

        assertThat(d.members()).containsExactly(PROFILE_2);   // 只有失败者回来
        assertThat(d.inflightMembers()).isEmpty();             // 整片都已 ack
        assertThat(m.load(PROFILE_3)).isEqualTo("{\"ok\":3}");
    }

    @Test
    void chunkSizeOneStillWorks() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set(PROFILE_1, "{}");
        r.set(BAG_2, "[]");
        d.mark(PROFILE_1);
        d.mark(BAG_2);

        assertThat(orchestrator(1).flushOnce()).isEqualTo(2);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void flushOnceIsIdempotentWhenRunTwice() {
        RedisStore r = new RedisStore(redis);
        r.set(PROFILE_1, "{\"v\":1}");
        dirty().mark(PROFILE_1);
        FlushOrchestrator o = orchestrator(500);

        assertThat(o.flushOnce()).isEqualTo(1);
        assertThat(o.flushOnce()).isZero();     // 第二轮无事可做
        assertThat(new MongoStore(mongo, MONGO_DB).load(PROFILE_1)).isEqualTo("{\"v\":1}");
    }
}
