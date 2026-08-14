package io.github.brick.data.config;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DataAutoConfiguration 装配全部 primitives（Tasks 1-9）且 DataProperties 属性绑定正确。
 * client 懒连接，无需 live Redis/Mongo（primitives §3.4）。
 */
@SpringBootTest(classes = DataAutoConfiguration.class,
        properties = "game.data.redisPoolSize=150")
class DataAutoConfigurationTest {

    @Autowired
    ApplicationContext ctx;
    @Autowired
    DataProperties props;
    @Autowired
    LockScope lockScope;

    @Test
    void wiresAllPrimitives() {
        assertThat(ctx.getBean(RedisStore.class)).isNotNull();
        assertThat(ctx.getBean(MongoStore.class)).isNotNull();
        assertThat(ctx.getBean(CommitLua.class)).isNotNull();
        assertThat(ctx.getBean(DirtyLedger.class)).isNotNull();
        assertThat(ctx.getBean(JsonCodec.class)).isNotNull();
        assertThat(ctx.getBean(LockScope.class)).isSameAs(lockScope);
    }

    @Test
    void propertiesBound() {
        assertThat(props.getRedisPoolSize()).isEqualTo(150);
        assertThat(props.getMongoPoolSize()).isBetween(50, 100);
        assertThat(props.getLockLeaseSeconds()).isEqualTo(10L);   // 固定租约无看门狗
    }
}
