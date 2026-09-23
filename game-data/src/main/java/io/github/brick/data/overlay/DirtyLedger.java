package io.github.brick.data.overlay;

import io.github.brick.data.store.DataKeys;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 按桶分片的 dirty 账本，落盘进程消费（提交桶化设计 §5，修订落盘 spec §2）。
 * 脏集合 {@code {bNNNN}::dirty} 与 in-flight 集合 {@code {bNNNN}::dirty:inflight} 和它标记的
 * 数据 key 同桶同 slot。集合名唯一来源在本类，{@link CommitLua} 引用
 * {@link #dirtySetOf(String)}。集合操作用 {@link StringCodec}：与 {@link CommitLua}
 * Lua 写入的裸 key 字符串对齐。
 *
 * <p><b>桶是排空、回写、ack 的作用域。</b>落盘 spec §2.1–§2.4 的论证对每个桶原样成立：
 * 消费协议是「原子排空到桶内 in-flight」——排空后该桶 dirty 立刻变空集，落盘期间的新标记
 * 进新一轮，物理隔离；in-flight 残留由下一轮 {@link #drainAll()} 的恢复步骤合回，
 * 恢复路径唯一。**刻意不提供「清空 inflight」的方法**：inflight 非空却被 DEL，正是丢标记。
 *
 * <p><b>每轮无条件排空全部 {@link DataKeys#BUCKETS} 个桶</b>（提交桶化设计 §5.2）：不做
 * 「先探测非空再排空」——中断轮次的桶只剩 inflight 残留（dirty 已被 RENAME 走），探测
 * dirty 键会漏掉它们，残留永远等不到合回。空桶的 drain 是微秒级 no-op，自带探测。K 个桶的
 * 批量操作用异步接口扇出、聚合等待，不逐桶串行往返（测试连接池 8 也能几十毫秒扫完）。
 */
public final class DirtyLedger {

    /**
     * 一次往返完成三件事：合回上轮残留、原子排空、返回快照。KEYS 是**单桶**的
     * (dirty, inflight)——同桶同 slot，Cluster 下合法（提交桶化设计 §5.1）。
     * {@code RENAME} 对不存在的 key 会报错，故排空前必须先 {@code EXISTS} 判空。
     */
    private static final String DRAIN_SCRIPT =
            "if redis.call('EXISTS', KEYS[2]) == 1 then " +
            "  redis.call('SUNIONSTORE', KEYS[1], KEYS[1], KEYS[2]); " +
            "  redis.call('DEL', KEYS[2]); " +
            "end; " +
            "if redis.call('EXISTS', KEYS[1]) == 0 then return {} end; " +
            "redis.call('RENAME', KEYS[1], KEYS[2]); " +
            "return redis.call('SMEMBERS', KEYS[2])";

    private final RedissonClient client;

    public DirtyLedger(RedissonClient client) {
        this.client = client;
    }

    /** 桶 b 的脏集合名：{@code {bNNNN}::dirty}。集合名唯一来源。 */
    public static String dirtySetOf(int bucket) {
        return String.format("{b%04d}::dirty", bucket);
    }

    /** 桶 b 的 in-flight 集合名：{@code {bNNNN}::dirty:inflight}。 */
    public static String inflightSetOf(int bucket) {
        return String.format("{b%04d}::dirty:inflight", bucket);
    }

    /** 由数据 key 推导其脏集合名——{@link CommitLua} 的 KEYS[2]。 */
    public static String dirtySetOf(String key) {
        return dirtySetOf(DataKeys.bucketOf(key));
    }

    /** 标脏单个 key（诊断/测试用；生产提交走 {@link CommitLua} 的原子 Lua）。 */
    public void mark(String key) {
        client.getSet(dirtySetOf(key), StringCodec.INSTANCE).add(key);
    }

    /**
     * 每轮排空入口：对全部 {@link DataKeys#BUCKETS} 个桶并发执行 DRAIN_SCRIPT（含残留合回）。
     * 空桶返回空集，不出现在结果里。
     *
     * @return 桶号 → 本轮成员快照；只含非空桶
     */
    public Map<Integer, Set<String>> drainAll() {
        RScript script = client.getScript(StringCodec.INSTANCE);
        List<CompletableFuture<List<Object>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(script.<List<Object>>evalAsync(
                    RScript.Mode.READ_WRITE, DRAIN_SCRIPT, RScript.ReturnType.LIST,
                    List.of(dirtySetOf(b), inflightSetOf(b))).toCompletableFuture());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        for (int b = 0; b < futures.size(); b++) {
            List<Object> members = futures.get(b).join();
            if (members == null || members.isEmpty()) {
                continue;
            }
            Set<String> snapshot = new LinkedHashSet<>(members.size());
            for (Object m : members) {
                snapshot.add(m.toString());
            }
            result.put(b, snapshot);
        }
        return result;
    }

    /**
     * 单桶排空（测试/诊断用）。生产轮次走 {@link #drainAll()}。
     *
     * @return 本桶本轮要落盘的 key 快照；空桶返回空 Set
     */
    public Set<String> drainToInflight(int bucket) {
        List<Object> members = client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, DRAIN_SCRIPT, RScript.ReturnType.LIST,
                List.of(dirtySetOf(bucket), inflightSetOf(bucket)));
        Set<String> snapshot = new LinkedHashSet<>();
        for (Object m : members) {
            snapshot.add(m.toString());
        }
        return snapshot;
    }

    /**
     * 一批落盘完成，把成员移出各自桶的 in-flight。**必须在 {@link #markAll} 回写失败
     * key 之后调用**（落盘 spec §2.4）：反序则「ack 后、mark 前」崩溃会让失败 key 既不在
     * in-flight 也不在 dirty，静默丢标记。批内 key 可跨桶——按 key 推导桶分组下发。
     */
    public void ackInflight(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byBucket = groupByBucket(keys);
        List<RFuture<Boolean>> futures = new ArrayList<>(byBucket.size());
        for (Map.Entry<Integer, List<String>> e : byBucket.entrySet()) {
            futures.add(inflightOf(e.getKey()).removeAllAsync(new ArrayList<>(e.getValue())));
        }
        awaitAll(futures);
    }

    /** 落盘失败的 key 回写各自桶的 dirty 等下轮重试。upsert 幂等，重做无害。 */
    public void markAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byBucket = groupByBucket(keys);
        List<RFuture<Boolean>> futures = new ArrayList<>(byBucket.size());
        for (Map.Entry<Integer, List<String>> e : byBucket.entrySet()) {
            futures.add(setOf(e.getKey()).addAllAsync(new ArrayList<>(e.getValue())));
        }
        awaitAll(futures);
    }

    /** 全部桶的 dirty 成员聚合（诊断/测试用）。K 次 SMEMBERS 异步扇出。 */
    public Set<String> members() {
        List<RFuture<Set<String>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(setOf(b).readAllAsync());
        }
        awaitAll(futures);
        Set<String> all = new LinkedHashSet<>();
        for (RFuture<Set<String>> f : futures) {
            Set<String> members = f.toCompletableFuture().join();
            if (members != null) {
                all.addAll(members);
            }
        }
        return all;
    }

    /** 当前 in-flight（已排空但尚未落盘）成员聚合。K 次 SMEMBERS 扇出，仅停机日志用。 */
    public Set<String> inflightMembers() {
        List<RFuture<Set<String>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(inflightOf(b).readAllAsync());
        }
        awaitAll(futures);
        Set<String> all = new LinkedHashSet<>();
        for (RFuture<Set<String>> f : futures) {
            Set<String> members = f.toCompletableFuture().join();
            if (members != null) {
                all.addAll(members);
            }
        }
        return all;
    }

    /** dirty 积压量（全部桶求和，不含 in-flight）。K 次 SCARD 扇出——缺键返回 0，不报错。 */
    public int backlogSize() {
        return dirtySizes().members();
    }

    /** 非空脏桶数。gauge {@code dbserver.dirty.buckets} 用（提交桶化设计 §6）。 */
    public int dirtyBucketCount() {
        return dirtySizes().nonEmptyBuckets();
    }

    private record Sizes(int members, int nonEmptyBuckets) {}

    private Sizes dirtySizes() {
        List<RFuture<Integer>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(setOf(b).sizeAsync());
        }
        awaitAll(futures);
        int members = 0;
        int nonEmpty = 0;
        for (RFuture<Integer> f : futures) {
            int n = f.toCompletableFuture().join();
            members += n;
            if (n > 0) {
                nonEmpty++;
            }
        }
        return new Sizes(members, nonEmpty);
    }

    private static Map<Integer, List<String>> groupByBucket(Collection<String> keys) {
        Map<Integer, List<String>> byBucket = new LinkedHashMap<>();
        for (String key : keys) {
            byBucket.computeIfAbsent(DataKeys.bucketOf(key), k -> new ArrayList<>()).add(key);
        }
        return byBucket;
    }

    private static void awaitAll(List<? extends RFuture<?>> futures) {
        CompletableFuture.allOf(futures.stream()
                .map(RFuture::toCompletableFuture)
                .toArray(CompletableFuture[]::new)).join();
    }

    private RSet<String> setOf(int bucket) {
        return client.getSet(dirtySetOf(bucket), StringCodec.INSTANCE);
    }

    private RSet<String> inflightOf(int bucket) {
        return client.getSet(inflightSetOf(bucket), StringCodec.INSTANCE);
    }
}
