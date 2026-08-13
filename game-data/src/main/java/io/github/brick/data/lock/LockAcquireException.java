package io.github.brick.data.lock;

/**
 * 拿锁失败：{@code LockScope.lockAll} 在 all-or-nothing 回滚已获取的锁后抛此异常
 *（并发修订 §1.3，primitives §2.1、§3.3）。
 */
public class LockAcquireException extends RuntimeException {
    public LockAcquireException(String message) { super(message); }
    public LockAcquireException(String message, Throwable cause) { super(message, cause); }
}
