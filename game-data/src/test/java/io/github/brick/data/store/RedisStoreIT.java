package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RedisStoreIT extends LocalRedisMongo {

    private RedisStore store() {
        return new RedisStore(redis);
    }

    @Test
    void setGetRoundTripRawString() {
        RedisStore s = store();
        s.set("player:1:profile", "{\"name\":\"alice\"}");
        assertThat(s.get("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
    }

    @Test
    void getMissingReturnsNull() {
        assertThat(store().get("player:1:profile")).isNull();
    }

    @Test
    void delRemoves() {
        RedisStore s = store();
        s.set("player:1:profile", "{}");
        s.del("player:1:profile");
        assertThat(s.get("player:1:profile")).isNull();
    }

    @Test
    void storesRawStringNotQuotedJson() {
        RedisStore s = store();
        String raw = "player:1:profile";   // 一个裸字符串，若被 JsonJacksonCodec 会带引号
        s.set("k", raw);
        assertThat(s.get("k")).isEqualTo(raw);
    }

    @Test
    void mgetReturnsAllPresentValues() {
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        s.set("player:2:bag", "[]");
        assertThat(s.mget(List.of("player:1:profile", "player:2:bag")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "player:1:profile", "{\"a\":1}",
                        "player:2:bag", "[]"));
    }

    @Test
    void mgetOmitsMissingKeysRatherThanMappingToNull() {
        // 该行为是落盘 spec §3「nil key 跳过且不回 dirty」的实现基础：
        // FlushOrchestrator 靠 chunk.size() - map.size() 算 nil 数量，
        // 若 Redisson 改为映射到 null，那个算法会静默失效。
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        Map<String, String> got = s.mget(List.of("player:1:profile", "player:404:bag"));
        assertThat(got).containsOnlyKeys("player:1:profile");
    }

    @Test
    void mgetEmptyInputReturnsEmptyMapWithoutTouchingRedis() {
        assertThat(new RedisStore(redis).mget(List.of())).isEmpty();
    }

    @Test
    void mgetReturnsRawStringNotQuotedJson() {
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        // StringCodec 对齐：若误用默认 JsonJacksonCodec，取回的会是带引号转义的字符串
        assertThat(s.mget(List.of("player:1:profile")).get("player:1:profile"))
                .isEqualTo("{\"a\":1}");
    }
}
