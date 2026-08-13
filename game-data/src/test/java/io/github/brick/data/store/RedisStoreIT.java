package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;
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
}
