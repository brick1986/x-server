package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommitLuaIT extends LocalRedisMongo {

    private static final String PROFILE = DataKeys.key("player", 1, "profile");

    @Test
    void commitSetsValueAndMarksDirty() {
        CommitLua lua = new CommitLua(redis);
        lua.commit(PROFILE, "{\"name\":\"alice\"}");

        RedisStore rs = new RedisStore(redis);
        assertThat(rs.get(PROFILE)).isEqualTo("{\"name\":\"alice\"}");
        assertThat(new DirtyLedger(redis).members()).containsExactly(PROFILE);
    }

    @Test
    void commitOverwritesExistingValue() {
        CommitLua lua = new CommitLua(redis);
        lua.commit(PROFILE, "{\"v\":1}");
        lua.commit(PROFILE, "{\"v\":2}");
        assertThat(new RedisStore(redis).get(PROFILE)).isEqualTo("{\"v\":2}");
    }
}
