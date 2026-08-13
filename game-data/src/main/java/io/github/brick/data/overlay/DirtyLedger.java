package io.github.brick.data.overlay;

import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;
import java.util.Set;

/**
 * dirty 集合账本：{@code SADD}/{@code SMEMBERS}/{@code SREM}，落盘进程（Plan C）消费。
 * dirty 集合名 {@link #DIRTY_SET} 是唯一来源，{@link CommitLua} 引用同一常量。
 * {@link RSet} 用 {@link StringCodec}：与 {@link CommitLua} Lua 写入的裸 key 字符串对齐（决策 5）。
 */
public final class DirtyLedger {

    public static final String DIRTY_SET = "dirty";

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

    private RSet<String> set() {
        return client.getSet(DIRTY_SET, StringCodec.INSTANCE);
    }
}
