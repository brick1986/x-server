package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 落盘指标（落盘 spec §7.1）。
 *
 * <p><b>{@code dbserver.dirty.backlog} 是最该配告警的那个</b>：它持续增长说明落盘跟不上写入，
 * 数据在 Redis 里越积越多、只有 AOF everysec 兜底。其余指标基本只在事后排障时有用。
 *
 * <p>backlog 与 buckets 两个 gauge 各自独立做一次 K-SCARD 扫描（gauge 抓取一次批量往返，
 * 抓取间隔 15s 级别下开销可忽略）——落盘进程本身就在高频访问 Redis。
 */
public class FlushMetrics {

    private final Timer round;
    private final Counter flushedKeys;
    private final Counter failedKeys;
    private final Counter missingKeys;
    private final Counter skippedRounds;

    public FlushMetrics(MeterRegistry registry, DirtyLedger dirty) {
        this.round = Timer.builder("dbserver.flush.round")
                .description("单轮落盘耗时——逼近 flush 周期说明该调大周期或分片")
                .register(registry);
        this.flushedKeys = Counter.builder("dbserver.flush.keys")
                .description("成功落盘的 key 数")
                .register(registry);
        this.failedKeys = Counter.builder("dbserver.flush.failed")
                .description("落盘失败并回写 dirty 的 key 数——持续非零即毒丸 key 告警")
                .register(registry);
        this.missingKeys = Counter.builder("dbserver.flush.missing")
                .description("标记尚存但 Redis 已无该 key 的数量")
                .register(registry);
        this.skippedRounds = Counter.builder("dbserver.flush.skipped")
                .description("抢不到落盘锁而跳过的轮次——热备实例上属正常，主实例上持续非零才异常")
                .register(registry);
        Gauge.builder("dbserver.dirty.backlog", dirty, DirtyLedger::backlogSize)
                .description("dirty 积压量——持续增长说明落盘跟不上写入")
                .register(registry);
        Gauge.builder("dbserver.dirty.buckets", dirty, DirtyLedger::dirtyBucketCount)
                .description("非空脏桶数——落盘健康的最先该看的信号，比成员数粗")
                .register(registry);
    }

    public void recordRound(long nanos, int flushed, int failed, int missing) {
        round.record(nanos, TimeUnit.NANOSECONDS);
        if (flushed > 0) {
            flushedKeys.increment(flushed);
        }
        if (failed > 0) {
            failedKeys.increment(failed);
        }
        if (missing > 0) {
            missingKeys.increment(missing);
        }
    }

    public void recordSkippedRound() {
        skippedRounds.increment();
    }
}
