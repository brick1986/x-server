package io.github.brick.data.overlay;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

/**
 * 原子提交脚本：{@code SET key json; SADD {bNNNN}::dirty key}，不解析 JSON（架构 spec §4.2，
 * primitives §3.2）。覆盖写唯一提交点——不暴露可分离的 {@code SET} 与 {@code SADD}，避免
 * 「SET 成功 SADD 失败致数据在 Redis 却不标脏、永不下沉 Mongo、Redis 崩即永久丢失」。
 *
 * <p>KEYS[2]（脏集合）由 key 推导：{@link DirtyLedger#dirtySetOf(String)}——桶号取自 key 的
 * {@code {bNNNN}} 前缀，集合与数据 key 同 hash tag 同 slot，这段跨 key Lua 在 Redis Cluster
 * 下合法（提交桶化设计 §4）。这也是架构 §4.2「不做跨 Key Lua」的修订兑现：Lua 只碰同一桶
 * 内的 key，跨实体实例的原子操作仍然不做。
 */
public final class CommitLua {

    private static final String SCRIPT =
            "redis.call('SET', KEYS[1], ARGV[1]); " +
            "redis.call('SADD', KEYS[2], KEYS[1]); " +
            "return 1";

    private final RedissonClient client;

    public CommitLua(RedissonClient client) {
        this.client = client;
    }

    public void commit(String key, String json) {
        client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                SCRIPT,
                RScript.ReturnType.LONG,
                List.of(key, DirtyLedger.dirtySetOf(key)),
                json);
    }
}
