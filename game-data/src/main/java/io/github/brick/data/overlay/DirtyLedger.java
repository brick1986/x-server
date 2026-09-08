package io.github.brick.data.overlay;

import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * dirty 集合账本，落盘进程消费（落盘 spec §2）。集合名 {@link #DIRTY_SET} / {@link #INFLIGHT_SET}
 * 是唯一来源，{@link CommitLua} 引用前者。{@link RSet} 用 {@link StringCodec}：与 {@link CommitLua}
 * Lua 写入的裸 key 字符串对齐。
 *
 * <p><b>两个集合名自带 Cluster hash tag。</b>{@code {dirty}} 与 {@code {dirty}:inflight} 的 tag
 * 同为 {@code dirty}，必然落同一 slot——二者在 {@link #DRAIN_SCRIPT} 同一段 Lua 里操作，
 * 不同 slot 会报 {@code CROSSSLOT}。单机模式下花括号只是普通字符，行为无差别。
 *
 * <p><b>消费协议是「原子排空到 in-flight」，不是「GET 后 SREM」</b>（落盘 spec §2.1 修订架构 §4.3）：
 * 后者会在「落盘进程读完 v1、业务侧写入 v2 并重新标脏」之后把 v2 的标记一并 {@code SREM} 掉，
 * 使已提交的值永不下沉。排空后 {@code dirty} 立刻是空集，落盘期间的新标记进新一轮，物理隔离。
 *
 * <p><b>{@link #INFLIGHT_SET} 的含义是「已排空但尚未落盘的 key」</b>：每片落盘完成即
 * {@link #ackInflight} 移除。故中断与崩溃路径无需任何专门处理——残留留在 inflight，
 * 由下一轮 {@link #drainToInflight} 的恢复步骤合回。**刻意不提供「清空 inflight」的方法**：
 * 若 inflight 非空却被 DEL，那正是上面那种丢标记。
 */
public final class DirtyLedger {

    public static final String DIRTY_SET = "{dirty}";

    public static final String INFLIGHT_SET = "{dirty}:inflight";

    /**
     * 一次往返完成三件事：合回上轮残留、原子排空、返回快照。
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

    public void mark(String key) {
        set().add(key);
    }

    public Set<String> members() {
        return set().readAll();
    }

    public void remove(String key) {
        set().remove(key);
    }

    public void removeAll(Collection<String> keys) {
        if (!keys.isEmpty()) {
            set().removeAll(keys);
        }
    }

    /**
     * 原子排空：先把上轮残留的 in-flight 合回 dirty，再把整个 dirty 改名为 in-flight 并返回快照。
     * 返回后 dirty 为空集，落盘期间业务侧的 {@code SADD} 进的是新一轮，不会被本轮吞掉。
     *
     * @return 本轮要落盘的 key 快照；dirty 与 in-flight 皆空时返回空 Set
     */
    public Set<String> drainToInflight() {
        List<Object> members = client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                DRAIN_SCRIPT,
                RScript.ReturnType.LIST,
                List.of(DIRTY_SET, INFLIGHT_SET));
        Set<String> snapshot = new LinkedHashSet<>();
        for (Object m : members) {
            snapshot.add(m.toString());
        }
        return snapshot;
    }

    /**
     * 一片落盘完成，把它移出 in-flight。**必须在 {@link #markAll} 回写失败 key 之后调用**
     * （落盘 spec §2.4）：反序则「ack 后、mark 前」崩溃会让失败 key 既不在 in-flight
     * 也不在 dirty，静默丢标记。
     */
    public void ackInflight(Collection<String> keys) {
        if (!keys.isEmpty()) {
            inflight().removeAll(keys);
        }
    }

    /** 落盘失败的 key 回写 dirty 等下轮重试。upsert 幂等，重做无害。 */
    public void markAll(Collection<String> keys) {
        if (!keys.isEmpty()) {
            set().addAll(keys);
        }
    }

    /** 诊断与测试用：当前 in-flight（已排空但尚未落盘）的 key。 */
    public Set<String> inflightMembers() {
        return inflight().readAll();
    }

    /** dirty 积压量（不含 in-flight）。落盘进程以此作 gauge 指标——持续增长说明落盘跟不上写入。 */
    public int backlogSize() {
        return set().size();
    }

    private RSet<String> set() {
        return client.getSet(DIRTY_SET, StringCodec.INSTANCE);
    }

    private RSet<String> inflight() {
        return client.getSet(INFLIGHT_SET, StringCodec.INSTANCE);
    }
}
