package io.github.brick.data.store;

import org.redisson.api.RBucket;
import org.redisson.api.RBuckets;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;
import java.util.Map;

/**
 * Redis 裸 JSON 字符串封装：{@code get}/{@code set}/{@code del}（连接池 100~200，见 DataProperties）。
 * 必须用 {@link StringCodec}：否则 Redisson 默认 JsonJacksonCodec 会把字符串存成带引号的 JSON，
 * 与 {@code CommitLua} 写入的裸字符串不一致（决策 5）。
 */
public final class RedisStore {

    private final RedissonClient client;

    public RedisStore(RedissonClient client) {
        this.client = client;
    }

    public String get(String key) {
        return bucket(key).get();
    }

    public void set(String key, String json) {
        bucket(key).set(json);
    }

    public void del(String key) {
        bucket(key).delete();
    }

    private RBucket<String> bucket(String key) {
        return client.getBucket(key, StringCodec.INSTANCE);
    }

    /**
     * 一次往返批量取值（{@code MGET}）。**不存在的 key 不出现在返回 Map 中**（不是映射到 null）
     * ——落盘进程据此识别「标记尚存但 key 已被删」的情形（落盘 spec §3）。
     *
     * <p>与 {@link #get}/{@link #set} 共用同一个 {@link StringCodec}：否则取回的是带引号的
     * JSON 字符串，与 {@code CommitLua} 写入的裸字符串不一致。
     */
    public Map<String, String> mget(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        return buckets().get(keys.toArray(String[]::new));
    }

    private RBuckets buckets() {
        return client.getBuckets(StringCodec.INSTANCE);
    }
}
