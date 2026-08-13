package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 实现：按全局类型优先级 + 同类型 ID 序排序后依次 tryLock（固定租约，无看门狗），
 * all-or-nothing（primitives §2.1、§3.3，并发修订 §1.3）。
 *
 * <p>使用 {@code tryLock(waitMillis, leaseSeconds, SECONDS)}：带 leaseTime 时 Redisson 不会启动看门狗续约，
 * 锁在固定租约后自动释放；waitMillis 内未获取即 fail-fast 返回 false（架构 §4.4）。
 */
public final class RedissonLockScope implements LockScope {

    private final RedissonClient client;
    private final RedisStore redis;
    private final MongoStore mongo;
    private final CommitLua commitLua;
    private final JsonCodec codec;
    private final long waitMillis;
    private final long leaseSeconds;
    private final Map<String, Integer> priority;   // entity → 优先级（小者先加锁）

    public RedissonLockScope(RedissonClient client, RedisStore redis, MongoStore mongo,
                             CommitLua commitLua, JsonCodec codec,
                             long waitMillis, long leaseSeconds,
                             Map<String, Integer> priority) {
        this.client = client;
        this.redis = redis;
        this.mongo = mongo;
        this.commitLua = commitLua;
        this.codec = codec;
        this.waitMillis = waitMillis;
        this.leaseSeconds = leaseSeconds;
        this.priority = priority;
    }

    @Override
    public LockCtx lockAll(List<LockReq> reqs) {
        List<LockReq> sorted = sortReqs(reqs, priority);
        List<RLock> acquired = new ArrayList<>(sorted.size());
        Map<String, RLock> byName = new LinkedHashMap<>();
        try {
            for (LockReq r : sorted) {
                String lockName = DataKeys.lockKey(r.entity(), r.id());
                RLock lock = client.getLock(lockName);
                // tryLock(waitMillis, leaseTime, MILLISECONDS)：waitMillis 为毫秒（fail-fast，架构 §4.4），
                // leaseSeconds 折算为毫秒；带 leaseTime 时 Redisson 不启动看门狗，固定租约后自动释放。
                boolean ok = lock.tryLock(waitMillis, leaseSeconds * 1000L, TimeUnit.MILLISECONDS);
                if (!ok) {
                    throw new LockAcquireException("加锁失败（被占）: " + lockName);
                }
                acquired.add(lock);
                byName.put(lockName, lock);
            }
        } catch (InterruptedException e) {
            rollback(acquired);
            Thread.currentThread().interrupt();
            throw new LockAcquireException("加锁被中断", e);
        } catch (LockAcquireException e) {
            rollback(acquired);
            throw e;
        } catch (RuntimeException e) {
            rollback(acquired);
            throw new LockAcquireException("加锁异常", e);
        }
        return new RedissonLockCtx(byName, acquired, redis, mongo, commitLua, codec);
    }

    /**
     * 排序：按实体类型全局优先级升序，同类型内按 id 升序（并发修订 §1.3）。
     * 包私有以供白盒测试断言顺序。未登记的实体类型抛 {@link IllegalArgumentException}（强制登记）。
     */
    static List<LockReq> sortReqs(List<LockReq> reqs, Map<String, Integer> priority) {
        // 先对每个 req 校验已登记（单元素列表排序不触发比较器，须显式校验以强制登记）
        for (LockReq r : reqs) {
            if (!priority.containsKey(r.entity())) {
                throw new IllegalArgumentException("未登记的实体类型: " + r.entity() + "，请在优先级表登记");
            }
        }
        List<LockReq> copy = new ArrayList<>(reqs);
        copy.sort(Comparator
                .comparingInt((LockReq r) -> priority.get(r.entity()))
                .thenComparingLong(LockReq::id));
        return copy;
    }

    /** 逆序释放已获取的锁，吞异常（锁可能已过期/非本线程持有）。 */
    private void rollback(List<RLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            try {
                acquired.get(i).unlock();
            } catch (Exception ignored) {
            }
        }
    }
}
