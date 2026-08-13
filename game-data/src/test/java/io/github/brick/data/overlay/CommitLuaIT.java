package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommitLuaIT extends LocalRedisMongo {

    @Test
    void commitSetsValueAndMarksDirty() {
        CommitLua lua = new CommitLua(redis);
        lua.commit("player:1:profile", "{\"name\":\"alice\"}");

        RedisStore rs = new RedisStore(redis);
        assertThat(rs.get("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
        assertThat(new DirtyLedger(redis).members()).containsExactly("player:1:profile");
    }

    @Test
    void commitOverwritesExistingValue() {
        CommitLua lua = new CommitLua(redis);
        lua.commit("player:1:profile", "{\"v\":1}");
        lua.commit("player:1:profile", "{\"v\":2}");
        assertThat(new RedisStore(redis).get("player:1:profile")).isEqualTo("{\"v\":2}");
    }
}
