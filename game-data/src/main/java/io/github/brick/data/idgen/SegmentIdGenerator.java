package io.github.brick.data.idgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模式发号（发号 spec §4）：进程向账本整段租号，内存顺次发。租段靠单文档原子性，
 * 多进程必然不重叠；进程崩溃丢段 = 空洞，无害。
 *
 * <p>并发形态：段内发号走 CAS（无锁）；段耗尽进 per-counter 互斥块串行租段，
 * double-check 后再租——排队期间已被别人租好就直接出去继续 CAS，不锁其他实体。
 *
 * <p>服号自检在首次发号时执行一次（懒，对齐 primitives §3.4 懒连接哲学：装配测试与
 * 不发号的模块不被迫触达 Mongo；真实部署中首次发号必然在最早期，fail-fast 语义不变，
 * 见 {@code MongoServerIdGuard}）。
 */
public final class SegmentIdGenerator implements IdGenerator {

    /** lease 单次上限，与配置校验的段长上限一致（发号 spec §5.1）。 */
    public static final int MAX_LEASE = 1_000_000;

    private final int serverId;
    private final int segmentSize;
    private final SegmentLeaser leaser;
    private final ServerIdGuard guard;
    private final ConcurrentHashMap<String, Segment> segments = new ConcurrentHashMap<>();
    private final AtomicBoolean checked = new AtomicBoolean();

    public SegmentIdGenerator(int serverId, int segmentSize,
                              SegmentLeaser leaser, ServerIdGuard guard) {
        if (segmentSize < 1 || segmentSize > MAX_LEASE) {
            throw new IllegalArgumentException("segmentSize 越界 [1," + MAX_LEASE + "]: " + segmentSize);
        }
        this.serverId = serverId;
        this.segmentSize = segmentSize;
        this.leaser = leaser;
        this.guard = guard;
    }

    @Override
    public long next(String name) {
        String counter = IdScheme.counterName(serverId, name);
        ensureChecked();
        Segment seg = segments.computeIfAbsent(counter, k -> new Segment());
        while (true) {
            long seq = seg.next.get();
            if (seq <= seg.end) {
                if (seg.next.compareAndSet(seq, seq + 1)) {
                    return IdScheme.compose(serverId, seq);
                }
                continue;   // 被并发抢走，重读
            }
            synchronized (seg) {   // 段耗尽：per-counter 串行租段
                if (seg.next.get() > seg.end) {   // double-check：排队期间可能已被租好
                    long end = leaser.lease(counter, segmentSize);
                    // 先备好 next、最后抬 end 开闸：若先抬 end，两写之间快路径已开，
                    // 并发线程按旧 next 发的号会被随后的 set 回滚重发——重号
                    seg.next.set(end - segmentSize + 1);
                    seg.end = end;
                }
            }
        }
    }

    @Override
    public long lease(String name, int count) {
        if (count < 1 || count > MAX_LEASE) {
            throw new IllegalArgumentException("count 越界 [1," + MAX_LEASE + "]: " + count);
        }
        String counter = IdScheme.counterName(serverId, name);
        ensureChecked();
        long end = leaser.lease(counter, count);
        return IdScheme.compose(serverId, end - count + 1);
    }

    private void ensureChecked() {
        if (checked.get()) {
            return;
        }
        synchronized (this) {
            if (!checked.get()) {
                guard.checkAndMark(serverId);
                checked.set(true);
            }
        }
    }

    /**
     * 本地段：{@code next} 是下一个可发流水、{@code end} 是段末位（含）。
     * 初值 next=1、end=0 即空段（1 > 0），首次发号由它触发租段。
     */
    private static final class Segment {
        final AtomicLong next = new AtomicLong(1);
        volatile long end;
    }
}
