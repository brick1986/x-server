package io.github.brick.data.overlay;

import io.github.brick.data.ClusterSlot;
import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirtyLedgerIT extends LocalRedisMongo {

    private static final String P1 = DataKeys.key("player", 1, "profile");
    private static final String P2 = DataKeys.key("player", 2, "bag");
    private static final String G7 = DataKeys.key("guild", 7, "fund");

    @Test
    void dataKeyAndLedgerSetsShareOneSlot() {
        // 设计 §8.2 的同槽断言——单机 IT 测不了 CROSSSLOT，Cluster 正确性靠它兜底
        int b = DataKeys.bucketOf(P1);
        assertThat(ClusterSlot.slot(P1))
                .isEqualTo(ClusterSlot.slot(DirtyLedger.dirtySetOf(b)))
                .isEqualTo(ClusterSlot.slot(DirtyLedger.inflightSetOf(b)));
    }

    @Test
    void dirtySetNamesFollowBucketGrammar() {
        assertThat(DirtyLedger.dirtySetOf(42)).isEqualTo("{b0042}::dirty");
        assertThat(DirtyLedger.inflightSetOf(42)).isEqualTo("{b0042}::dirty:inflight");
        assertThat(DirtyLedger.dirtySetOf(P1))
                .isEqualTo(DirtyLedger.dirtySetOf(DataKeys.bucketOf(P1)));
    }

    @Test
    void markRoutesToItsOwnBucket() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.members()).containsExactlyInAnyOrder(P1, P2);
        assertThat(d.drainToInflight(DataKeys.bucketOf(P1))).contains(P1);
    }

    @Test
    void membersStoresRawStringNotQuotedJson() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        assertThat(d.members()).first().isEqualTo(P1);
    }

    @Test
    void drainAllSweepsEveryBucketAndEmptiesDirty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(G7);
        assertThat(d.drainAll().values().stream().flatMap(Set::stream))
                .containsExactlyInAnyOrder(P1, G7);
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void drainAllOnEmptyRedisReturnsEmptyMap() {
        assertThat(new DirtyLedger(redis).drainAll()).isEmpty();
    }

    @Test
    void drainAllRecoversLeftoverInflightPerBucket() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.drainAll();                     // 第一轮排空后「崩溃」，不 ack
        d.mark(P2);                       // 期间业务侧又标脏一个

        // 下一轮必须把该桶残留合回，两者一起返回
        assertThat(d.drainAll().values().stream().flatMap(Set::stream))
                .containsExactlyInAnyOrder(P1, P2);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void drainOfEmptyBucketIsANoop() {
        // RENAME 对不存在的 key 会报错，Lua 必须先 EXISTS 判空——空桶 drain 不抛
        assertThat(new DirtyLedger(redis).drainToInflight(0)).isEmpty();
    }

    @Test
    void concurrentMarkDuringDrainIsNotSwallowed() {
        // 落盘 spec §2.1 的回归：排空期间的新标脏必须留在新一轮
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        Set<String> snapshot = d.drainToInflight(DataKeys.bucketOf(P1));

        d.mark(P1);                       // 落盘期间业务侧写 v2，重新标脏
        d.ackInflight(snapshot);          // 本轮 ack 自己的快照

        assertThat(d.members()).containsExactly(P1);
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void ackAndMarkRouteKeysToTheirOwnBuckets() {
        // markAll/ackInflight 按 key 推导桶分组——批内跨桶也能正确路由
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        d.mark(G7);
        d.drainAll();
        d.ackInflight(List.of(P1, P2));
        assertThat(d.inflightMembers()).containsExactly(G7);

        d.markAll(List.of(P1, G7));       // 失败回写：跨桶的两个 key
        assertThat(d.members()).containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void ackInflightAndMarkAllTolerateEmptyInput() {
        DirtyLedger d = new DirtyLedger(redis);
        d.ackInflight(List.of());
        d.markAll(List.of());
        assertThat(d.members()).isEmpty();
    }

    @Test
    void backlogSizeSumsBucketsAndBucketCountCountsNonEmpty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.backlogSize()).isEqualTo(2);
        // P1/P2 散列进 1 或 2 个桶（确定性但依实现而定），两个界都成立
        assertThat(d.dirtyBucketCount()).isBetween(1, 2);
        d.drainAll();
        assertThat(d.backlogSize()).isZero();   // 已排空，积压在 inflight 不算积压
    }
}
