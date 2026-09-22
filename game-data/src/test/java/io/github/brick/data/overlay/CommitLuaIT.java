package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommitLuaIT extends LocalRedisMongo {

    private static final String PROFILE = DataKeys.key("player", 1, "profile");

    @Test
    void commitSetsValueAndMarksDirtyInTheRightBucket() {
        CommitLua lua = new CommitLua(redis);
        lua.commit(PROFILE, "{\"name\":\"alice\"}");

        assertThat(new RedisStore(redis).get(PROFILE)).isEqualTo("{\"name\":\"alice\"}");

        // 标记必须落在 PROFILE 自己的桶集合里（与数据 key 同 slot）
        // 显式类型见证：V 只出现在 getSet 返回类型里，链式调用经 receiver 不回传目标类型，
        // 不加 <String> 会被 javac 推断成 Set<Object> 而编译失败
        Set<String> bucketMembers = redis.<String>getSet(
                DirtyLedger.dirtySetOf(DataKeys.bucketOf(PROFILE)), StringCodec.INSTANCE).readAll();
        assertThat(bucketMembers).containsExactly(PROFILE);
        // 聚合视图同样可见
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
