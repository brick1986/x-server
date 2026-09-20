package io.github.brick.dbserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 骨架上下文测试（Redisson 自动配置照旧排除）。启动阶段不连外部 Redis/Mongo——装配的是
 * game-data 自建的客户端：RedissonClient 开了 lazyInitialization（见 DataAutoConfiguration），
 * Mongo 客户端同样首次操作才建连，装配期无网络往返。但上下文 close 必调 GracefulShutdown.stop()
 * （autoStartup 的 SmartLifecycle），那里会真连 Redis 抢落盘锁——「不依赖外部 Redis」只对
 * 启动成立，对 teardown 不成立。两个测试属性各护一段：{@code game.dbserver.flush-interval-millis=60000}
 * 让 @Scheduled 落盘轮次在测试时长内不触发；{@code game.dbserver.shutdown-timeout-seconds=1}
 * 把 Redis 不可用时 stop() 的最坏阻塞压到 1s（默认 20s），teardown 不至于久等。
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
		// Task 8 起本进程有 @Scheduled 落盘轮次；骨架上下文测试不连 Redis，
		// 把间隔调到远大于测试时长，避免无意义的连接失败噪声。
		"game.dbserver.flush-interval-millis=60000",
		// Task 9 起停机刷盘是 autoStartup 的 SmartLifecycle，上下文 close 必调 stop()，
		// 会连 Redis 抢落盘锁；把硬超时压到 1s，teardown 不至于阻塞默认 20s。
		"game.dbserver.shutdown-timeout-seconds=1"
})
class DbServerApplicationTest {

	@Test
	void contextLoads() {
	}
}
