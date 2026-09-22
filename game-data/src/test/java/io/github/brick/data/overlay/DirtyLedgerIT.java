package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DirtyLedgerIT extends LocalRedisMongo {

    private static final String P1 = DataKeys.key("player", 1, "profile");
    private static final String P2 = DataKeys.key("player", 2, "bag");
    private static final String G7 = DataKeys.key("guild", 7, "fund");
    private static final String P1_BAG = DataKeys.key("player", 1, "bag");

    @Test
    void markAndMembers() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.members()).containsExactlyInAnyOrder(P1, P2);
    }

    @Test
    void removeDropsMember() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        d.remove(P1);
        assertThat(d.members()).containsExactly(P2);
    }

    @Test
    void removeAllDropsAll() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(G7);
        d.removeAll(Set.of(P1, G7));
        assertThat(d.members()).isEmpty();
    }

    @Test
    void membersStoresRawStringNotQuotedJson() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        assertThat(d.members()).first().isEqualTo(P1);
    }

    @Test
    void keyNamesCarryClusterHashTag() {
        // Cluster 下 {dirty} 与 {dirty}:inflight 的 hash tag 同为 dirty，必然同 slot；
        // 二者在同一段 Lua 里操作，不同 slot 会报 CROSSSLOT（落盘 spec §2.5）。
        assertThat(DirtyLedger.DIRTY_SET).isEqualTo("{dirty}");
        assertThat(DirtyLedger.INFLIGHT_SET).isEqualTo("{dirty}:inflight");
    }

    @Test
    void drainMovesEverythingToInflightAndEmptiesDirty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(G7);
        assertThat(d.drainToInflight())
                .containsExactlyInAnyOrder(P1, G7);
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers())
                .containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void drainOnEmptyDirtyReturnsEmptyInsteadOfFailing() {
        // RENAME 对不存在的 key 会报错，故 Lua 必须先 EXISTS 判空
        assertThat(new DirtyLedger(redis).drainToInflight()).isEmpty();
    }

    @Test
    void drainRecoversLeftoverInflightFromAnInterruptedRound() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.drainToInflight();                 // 第一轮排空后「崩溃」，不 ack
        d.mark(P2);                          // 期间业务侧又标脏一个

        // 下一轮 drain 必须把残留合回来，两者一起返回
        assertThat(d.drainToInflight())
                .containsExactlyInAnyOrder(P1, P2);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void concurrentMarkDuringFlushIsNotSwallowed() {
        // 落盘 spec §2.1 的回归测试：这正是被修订掉的「GET 后 SREM」会丢掉的东西
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1_BAG);
        Set<String> snapshot = d.drainToInflight();

        d.mark(P1_BAG);                      // 落盘期间业务侧写了 v2，重新标脏
        d.ackInflight(snapshot);             // 本轮落完 v1，只 ack 自己的快照

        // v2 的标记必须还在，下一轮会落它
        assertThat(d.members()).containsExactly(P1_BAG);
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void ackInflightRemovesOnlyThatChunk() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        d.mark(G7);
        d.drainToInflight();
        d.ackInflight(List.of(P1, P2));
        assertThat(d.inflightMembers()).containsExactly(G7);
    }

    @Test
    void ackInflightAndMarkAllTolerateEmptyInput() {
        DirtyLedger d = new DirtyLedger(redis);
        d.ackInflight(List.of());
        d.markAll(List.of());
        assertThat(d.members()).isEmpty();
    }

    @Test
    void markAllPutsFailedKeysBackForRetry() {
        DirtyLedger d = new DirtyLedger(redis);
        d.markAll(List.of(P1, G7));
        assertThat(d.members())
                .containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void backlogSizeCountsDirtyOnly() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.backlogSize()).isEqualTo(2);
        d.drainToInflight();
        assertThat(d.backlogSize()).isZero();   // 已排空，积压在 inflight 不算积压
    }
}
