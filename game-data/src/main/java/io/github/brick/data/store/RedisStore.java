package io.github.brick.data.store;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

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
}
