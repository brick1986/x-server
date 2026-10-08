package io.github.brick.data.idgen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentIdGeneratorTest {

    /** 按调用顺序返回预设段末位的 stub 账本。 */
    private static SegmentLeaser leaser(long... ends) {
        AtomicInteger i = new AtomicInteger();
        return (counter, count) -> ends[i.getAndIncrement()];
    }

    @Test
    void nextIssuesSeqWithinLeasedSegment() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        assertThat(g.next("player")).isEqualTo(IdScheme.compose(1, 1));   // 4294967297
        assertThat(g.next("player")).isEqualTo(IdScheme.compose(1, 2));
    }

    @Test
    void nextReleasesNewSegmentOnExhaustion() {
        // 段长 2：首租返回 2（段 [1,2]），第二租返回 6（段 [5,6]）
        SegmentIdGenerator g = new SegmentIdGenerator(1, 2, leaser(2L, 6L), s -> {});
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 1));
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 2));
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 5));
    }

    @Test
    void namesHaveIndependentSegments() {
        // counter 每 entity 独立、流水都从 1 起（发号 spec §3 表格）：
        // 两段各租 [1,100]，guild 首号仍从 1 起（若 counter 被共享则会拿到 2）
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L, 100L), s -> {});
        g.next("player");
        assertThat(g.next("guild")).isEqualTo(IdScheme.compose(1, 1));
    }

    @Test
    void leaseReturnsSegmentStart() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100000L), s -> {});
        assertThat(g.lease("mail", 100000)).isEqualTo(IdScheme.compose(1, 1));
    }

    @Test
    void firstCallTriggersServerIdCheckExactlyOnce() {
        List<Integer> marks = new CopyOnWriteArrayList<>();
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), marks::add);
        g.next("player");
        g.next("player");
        assertThat(marks).containsExactly(1);
    }

    @Test
    void concurrentNextWithinSegmentNeverDuplicates() throws Exception {
        // 96 个号 < 段长 100：全程一段内发完，stub 账本只备了一个返回值——
        // 若 CAS/锁有误触发二次租段，stub 越界直接炸，测试自然失败
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int n = 0; n < 12; n++) ids.add(g.next("player"));
                    return null;
                }));
            }
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertThat(ids).hasSize(96);
    }

    @Test
    void rejectsBadLeaseCount() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        assertThatThrownBy(() -> g.lease("mail", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.lease("mail", SegmentIdGenerator.MAX_LEASE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadSegmentSize() {
        assertThatThrownBy(() -> new SegmentIdGenerator(1, 0, leaser(1L), s -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentIdGenerator(1, SegmentIdGenerator.MAX_LEASE + 1, leaser(1L), s -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
