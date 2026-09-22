package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 一轮落盘的完整编排（落盘 spec §2~§4）。无状态，谁都能调——定时轮次调它，停机刷盘也调它。
 *
 * <p>流程：抢进程级落盘锁 → 原子排空 dirty → 按 {@code chunkSize} 分片 → 每片 {@code MGET} 取值
 * （还 Redis 连接）→ {@code bulkUpsert} 写 Mongo → 失败 key 回写 dirty → 整批移出 in-flight。
 *
 * <p><b>禁嵌套跨池</b>（并发修订 §3.2）：{@code mget} 返回时 Redis 连接已归还，之后才触达 Mongo，
 * 一个虚拟线程任一时刻只持有一个池的连接。
 *
 * <p><b>分片是为了让内存与单次往返量只与片大小有关、与积压总量无关</b>：冷启动首轮或积压上万时，
 * 逐个 {@code GET} 是上万次同步往返，而一次攒上万个 {@code Document} 才是真问题（落盘 spec §3）。
 *
 * <p><b>单实例保证</b>（落盘 spec §5）：{@code drainAll} 的 {@code RENAME} 是覆盖语义，
 * 多实例并发会直接丢标记，故每轮先抢进程级互斥锁。每批处理完检查持锁（与 {@code LockCtx.put}
 * 的提交门控同构）——租约到期即中断本轮，绝不带着失效锁继续写。
 *
 * <p><b>中断与崩溃路径零操作</b>：剩余 key 留在 in-flight，由下一轮 {@code drainAll}
 * 的恢复步骤合回 dirty（落盘 spec §2.3）。故本类没有任何「回滚」或「清理」分支。
 *
 * <p>轮次结构（提交桶化设计 §5.3）：第一段 K 桶全量排空；第二段逐桶 MGET、跨桶聚合
 * Mongo 批。每批完成检查持锁。批内先 markAll 失败 key 再 ackInflight 整批（落盘 spec §2.4）。
 */
public class FlushOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlushOrchestrator.class);

    /** {@link #flushOnce()} 的第三态：本轮没干成事（非「已刷空」）。见落盘 spec §3.1 返回值契约。 */
    public static final int INTERRUPTED = -1;

    /**
     * 落盘进程级互斥锁。**刻意不走 {@code DataKeys.lockKey()}**——那是 {@code lock:{entity}:{id}}
     * 的实体锁命名，本锁不是实体锁，不复用以免两种语义混淆（落盘 spec §5）。
     */
    public static final String FLUSH_LOCK = "lock:dbserver:flush";

    private final RedissonClient redisson;
    private final DirtyLedger dirty;
    private final RedisStore redis;
    private final MongoStore mongo;
    private final int chunkSize;
    private final long lockLeaseSeconds;
    private final FlushMetrics metrics;

    public FlushOrchestrator(RedissonClient redisson, DirtyLedger dirty, RedisStore redis,
                             MongoStore mongo, int chunkSize, long lockLeaseSeconds, FlushMetrics metrics) {
        this.redisson = redisson;
        this.dirty = dirty;
        this.redis = redis;
        this.mongo = mongo;
        this.chunkSize = chunkSize;
        this.lockLeaseSeconds = lockLeaseSeconds;
        this.metrics = metrics;
    }

    /**
     * 跑一轮落盘。
     *
     * @return 三态（落盘 spec §3.1 返回值契约）：<ul>
     *   <li>{@code 0} —— 排空后无 key，**只有这一种代表「真刷空了」**，停机循环据此收敛；</li>
     *   <li>{@code n > 0} —— 本轮排空的 key 数（含其中落盘失败、已回写 dirty 的）；</li>
     *   <li>{@link #INTERRUPTED} —— 抢不到锁（另一实例在落盘）或中途失锁，本轮没干成事。</li></ul>
     *   返回排空数而非成功数是刻意的：若一批全部失败却返回 0，停机循环会把「dirty 里还有货」
     *   误判成刷完而提前退出。
     * @throws com.mongodb.MongoException Mongo 连接级故障，本轮中断；剩余 key 留在 in-flight
     */
    public int flushOnce() {
        RLock lock = redisson.getLock(FLUSH_LOCK);
        boolean acquired;
        try {
            // tryLock(0, lease, …)：不等待，抢不到立刻走。固定租约、无看门狗（并发修订 §4.1）
            acquired = lock.tryLock(0L, lockLeaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return INTERRUPTED;
        }
        if (!acquired) {
            // 这是热备实例的**正常**状态，不是故障，故 DEBUG 而非 WARN
            log.debug("未抢到落盘锁，本轮跳过（另一实例正在落盘）");
            metrics.recordSkippedRound();
            return INTERRUPTED;
        }
        try {
            return drainAndFlush(lock);
        } finally {
            // 必须先判 isHeld：租约已到期时 unlock() 会抛 IllegalMonitorStateException，
            // 那会盖掉 try 块里真正的异常
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private int drainAndFlush(RLock lock) {
        long startNanos = System.nanoTime();
        int flushed = 0, failed = 0, missing = 0;

        // 第一段：K 桶全量排空（含上轮残留合回），得 桶号 → 本轮成员快照
        Map<Integer, Set<String>> drained = dirty.drainAll();
        int total = 0;
        for (Set<String> members : drained.values()) {
            total += members.size();
        }
        if (total == 0) {
            metrics.recordRound(System.nanoTime() - startNanos, 0, 0, 0);
            return 0;
        }

        // 第二段：逐桶 MGET（同桶成员同 slot，Cluster 下单条 MGET 合法），
        // 值跨桶聚成 Mongo 批（批量收益与总量相关、与桶数无关）
        Batch batch = new Batch(chunkSize);
        for (Map.Entry<Integer, Set<String>> e : drained.entrySet()) {
            for (List<String> mchunk : chunks(e.getValue(), chunkSize)) {
                batch.add(mchunk, redis.mget(mchunk));
                while (batch.isFull()) {
                    ChunkStats s = flushBatch(batch.members(), batch.values());
                    flushed += s.flushed();
                    failed += s.failed();
                    missing += s.missing();
                    batch.clear();
                    if (!stillHoldsLock(lock)) {
                        // 与 LockCtx.put 的 isHeld 提交门控同构：绝不带着失效锁继续写。
                        // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
                        log.error("落盘中途失锁（租约 {}s 到期），中断本轮；剩余 {} 个 key 留在 in-flight 待下轮恢复",
                                lockLeaseSeconds, dirty.inflightMembers().size());
                        metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
                        return INTERRUPTED;
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            ChunkStats s = flushBatch(batch.members(), batch.values());
            flushed += s.flushed();
            failed += s.failed();
            missing += s.missing();
        }
        metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
        return total;
    }

    /**
     * 是否仍持有落盘锁。**受保护仅为可测**——「跑到第 N 批时失锁」用真实租约到期无法稳定复现，
     * 测试子类覆盖本方法即可确定性地触发中断分支。生产语义就是 isHeldByCurrentThread()。
     */
    protected boolean stillHoldsLock(RLock lock) {
        return lock.isHeldByCurrentThread();
    }

    /** 单批落盘统计，仅用于指标累计。包级可见——测试子类覆盖 {@code flushBatch} 时须能命名返回类型（同 {@link #chunks} 的先例）。 */
    record ChunkStats(int flushed, int failed, int missing) {}

    /**
     * 一个 Mongo 批的落盘。批内成员可能跨桶（MGET 已按桶分完组，这里只剩值搬运）。
     * 顺序约束（落盘 spec §2.4）：先 markAll 失败 key、后 ackInflight 整批成员。
     *
     * <p>受保护仅为可测——「跑到第 N 批时 Mongo 抛异常」用真实故障无法确定性复现，
     * 测试子类覆盖本方法即可触发（同 {@link #chunks} 的先例）。
     */
    protected ChunkStats flushBatch(List<String> members, Map<String, String> values) {
        // mget 不把不存在的 key 映射到 null，而是不放进 Map，故差值即 nil 数量。
        // 这些 key 跳过 upsert 且**不回 dirty**——回了就是永久重试的死循环。
        int missing = members.size() - values.size();
        if (missing > 0) {
            log.warn("本批 {} 个 key 在 Redis 已不存在（标记残留），跳过落盘且不回写 dirty", missing);
        }

        Set<String> failed = mongo.bulkUpsert(values);
        if (!failed.isEmpty()) {
            log.warn("落盘失败 {} 个 key，回写 dirty 等下轮重试: {}", failed.size(), failed);
            dirty.markAll(failed);      // 顺序要求：必须先 mark 再 ack（落盘 spec §2.4）
        }
        dirty.ackInflight(members);     // 两步之间崩溃只会导致整批被下轮重放，upsert 幂等
        return new ChunkStats(values.size() - failed.size(), failed.size(), missing);
    }

    /** 跨桶聚合的 Mongo 批缓冲：成员数到 chunkSize 即成批（与旧「片」同尺寸语义）。 */
    private static final class Batch {
        private final int capacity;
        private final List<String> members = new ArrayList<>();
        private final Map<String, String> values = new LinkedHashMap<>();

        Batch(int capacity) {
            this.capacity = capacity;
        }

        void add(List<String> mchunk, Map<String, String> vals) {
            members.addAll(mchunk);
            values.putAll(vals);
        }

        boolean isFull() {
            return members.size() >= capacity;
        }

        boolean isEmpty() {
            return members.isEmpty();
        }

        void clear() {
            members.clear();
            values.clear();
        }

        List<String> members() {
            return members;
        }

        Map<String, String> values() {
            return values;
        }
    }

    /** 定长切片。包级可见供单测——切分边界（空集/整数倍/余数）值得独立于 Redis 验证。 */
    static List<List<String>> chunks(Collection<String> keys, int size) {
        List<String> all = new ArrayList<>(keys);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return out;
    }
}
