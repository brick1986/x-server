package io.github.brick.data.lock;

/**
 * 失锁：{@code LockCtx.get} 持锁断言失败、{@code LockCtx.put} 提交门控失败（isHeld=false）
 * 均抛此异常，使块内任意失锁点走同一条重试/中止路径（primitives §2.2、§4）。
 */
public class LockLostException extends RuntimeException {
    public LockLostException(String message) { super(message); }
}
