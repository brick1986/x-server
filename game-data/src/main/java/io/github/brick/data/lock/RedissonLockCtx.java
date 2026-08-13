package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redisson 实现。持有按 acquire 顺序的锁列表（close 逆序释放）+ 锁名→RLock 映射（resolveLock 命中本实体锁）。
 * 安全不变量在此一处闭环：持锁断言、提交门控、原子提交、逆序放锁（primitives §2.2、§3.1）。
 *
 * <p>仅暴露 {@code (Map<String,RLock> locksByName, List<RLock> order, …)} 构造器。不提供单参
 * {@code List<RLock>} 构造器——{@code RLock} 不暴露 entity/id，无法由锁列表可靠推导锁名，
 * resolveLock 会查不到而误抛。LockScope 与测试均直传锁名映射。
 */
public final class RedissonLockCtx implements LockCtx {

    private final List<RLock> order;                       // acquire 顺序，close 逆序
    private final Map<String, RLock> locksByName;          // lock:{entity}:{id} → RLock
    private final RedisStore redis;
    private final MongoStore mongo;
    private final CommitLua commitLua;
    private final JsonCodec codec;
    private boolean closed = false;

    /** LockScope/测试用：直传锁名映射 + 顺序。锁名 = {@link DataKeys#lockKey(String, long)}。 */
    public RedissonLockCtx(Map<String, RLock> locksByName, List<RLock> order,
                           RedisStore redis, MongoStore mongo, CommitLua commitLua, JsonCodec codec) {
        this.order = List.copyOf(order);
        this.locksByName = new LinkedHashMap<>(locksByName);
        this.redis = redis;
        this.mongo = mongo;
        this.commitLua = commitLua;
        this.codec = codec;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        RLock lock = resolveLock(key);
        if (!lock.isHeldByCurrentThread()) {
            throw new LockLostException("读路径失锁: " + key);
        }
        String v = redis.get(key);
        if (v == null) {
            v = mongo.load(key);          // 锁内从 Mongo 加载
            if (v != null) {
                redis.set(key, v);         // 普通回填（持锁，无并发写，无需 SET NX）
            }
        }
        return codec.decode(v, type);
    }

    @Override
    public <T> void put(String key, T pojo) {
        String json = codec.encode(pojo);
        RLock lock = resolveLock(key);
        if (!lock.isHeldByCurrentThread()) {     // 提交门控
            throw new LockLostException("提交门控失锁: " + key);
        }
        commitLua.commit(key, json);             // 唯一提交点，原子 SET+SADD
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // 逆序释放，异常路径也执行（try-with-resources 强制）；已由 TTL 释放的 unlock 抛异常忽略
        for (int i = order.size() - 1; i >= 0; i--) {
            try {
                order.get(i).unlock();
            } catch (Exception ignored) {
                // 锁已过期/非本线程持有，忽略
            }
        }
    }

    /** 由 data key 解析到本实体的 RLock（primitives §3.1 resolveLock 命中本实体锁）。 */
    private RLock resolveLock(String key) {
        String lockName = DataKeys.lockKey(DataKeys.entityOf(key), DataKeys.idOf(key));
        RLock lock = locksByName.get(lockName);
        if (lock == null) {
            throw new LockLostException("未持锁即调用: " + key + "（锁名 " + lockName + "）");
        }
        return lock;
    }
}
