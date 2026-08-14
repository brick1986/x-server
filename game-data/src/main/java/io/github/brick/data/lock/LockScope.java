package io.github.brick.data.lock;

import java.util.List;

/**
 * 锁作用域入口：按全局类型优先级 + 同类型 ID 序排序后依次 acquire，all-or-nothing
 *（primitives §2.1、§3.3，并发修订 §1.3）。固定租约无看门狗；任一锁失败回滚已获取的锁后抛
 * {@link LockAcquireException}。返回的 {@link LockCtx} 须以 try-with-resources 包住。
 */
public interface LockScope {

    /**
     * 对给定请求按 (entity 优先级 ASC, id ASC) 排序后依次加锁；全部成功才返回 ctx，否则回滚已获取的锁并抛
     * {@link LockAcquireException}。
     */
    LockCtx lockAll(List<LockReq> reqs);
}
