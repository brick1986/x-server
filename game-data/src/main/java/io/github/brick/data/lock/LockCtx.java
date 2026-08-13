package io.github.brick.data.lock;

/**
 * 锁作用域：承载锁内读与覆盖写。必须用 try-with-resources 包住，{@code close()} 逆序释放全部锁
 *（primitives §2.2、§3.1）。
 */
public interface LockCtx extends AutoCloseable {

    /** 锁内读。miss 则锁内从 Mongo 加载并普通 SET 回填。失锁抛 {@link LockLostException}。 */
    <T> T get(String key, Class<T> type);

    /** 锁内覆盖写：序列化 + isHeld 门控 + 原子 Lua SET+SADD。单 key。失锁抛 {@link LockLostException}。 */
    <T> void put(String key, T pojo);

    @Override
    void close();
}
