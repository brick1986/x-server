package io.github.brick.data.config;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.EntityPriorities;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 DataAutoConfiguration 装配 primitives、DataProperties 属性绑定与校验，以及
 * LockScope 依赖业务侧 {@link EntityPriorities} 的条件装配（game-data 不认识业务实体）。
 * client 懒连接，无需 live Redis/Mongo（primitives §3.4）。
 */
class DataAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(DataAutoConfiguration.class));

	/** 业务模块（game-web）登记自身实体优先级的等价物。game-data 自身不含此表。 */
	@Configuration
	static class TestPriorities {
		@Bean
		EntityPriorities entityPriorities() {
			return () -> Map.of("guild", 0, "player", 1);
		}
	}

	@Test
	void wiresAllPrimitives() {
		runner.withUserConfiguration(TestPriorities.class).run(ctx -> assertThat(ctx)
				.hasSingleBean(RedisStore.class)
				.hasSingleBean(MongoStore.class)
				.hasSingleBean(CommitLua.class)
				.hasSingleBean(DirtyLedger.class)
				.hasSingleBean(JsonCodec.class));
	}

	@Test
	void propertiesBound() {
		runner.withPropertyValues("game.data.redisPoolSize=150").run(ctx -> {
			DataProperties p = ctx.getBean(DataProperties.class);
			assertThat(p.getRedisPoolSize()).isEqualTo(150);
			assertThat(p.getMongoPoolSize()).isBetween(50, 100);
			assertThat(p.getLockLeaseSeconds()).isEqualTo(10L);   // 固定租约无看门狗
		});
	}

	// 业务模块登记了实体优先级 → LockScope 可用，且用的就是业务侧那张表
	@Test
	void lockScopeWiredWhenEntityPrioritiesPresent() {
		runner.withUserConfiguration(TestPriorities.class)
				.run(ctx -> assertThat(ctx).hasSingleBean(LockScope.class));
	}

	// 不加锁的模块（game-dbserver）未登记 → 无 LockScope，但上下文照常启动、其余原语可用
	@Test
	void lockScopeAbsentWhenEntityPrioritiesMissing() {
		runner.run(ctx -> assertThat(ctx)
				.hasNotFailed()
				.doesNotHaveBean(LockScope.class)
				.hasSingleBean(RedisStore.class)
				.hasSingleBean(DirtyLedger.class));
	}

	// 池大小越界必须启动失败——spec「显式设限」不能只活在注释里
	@Test
	void rejectsRedisPoolSizeBelowFloor() {
		runner.withUserConfiguration(TestPriorities.class)
				.withPropertyValues("game.data.redisPoolSize=5")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void rejectsMongoPoolSizeAboveCeiling() {
		runner.withUserConfiguration(TestPriorities.class)
				.withPropertyValues("game.data.mongoPoolSize=500")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	// 密码可配，且默认不配（连无鉴权实例）
	@Test
	void redisPasswordBoundWhenProvided() {
		runner.withPropertyValues(
						"game.data.redisPassword=s3cret",
						"game.data.redisUsername=gamer")
				.run(ctx -> {
					DataProperties p = ctx.getBean(DataProperties.class);
					assertThat(p.getRedisPassword()).isEqualTo("s3cret");
					assertThat(p.getRedisUsername()).isEqualTo("gamer");
				});
	}

	// 空密码（yml 里 ${REDIS_PASSWORD:} 未设环境变量的常态）不得被当成「有密码」而发 AUTH，
	// 否则连无鉴权 Redis 会失败。此处断言客户端能建起来，即走了「不 setPassword」分支。
	@Test
	void blankRedisPasswordStillBuildsClient() {
		runner.withPropertyValues("game.data.redisPassword=")
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx.getBean(DataProperties.class).getRedisPassword()).isEmpty();
				});
	}
}
