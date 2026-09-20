package io.github.brick.dbserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 排除 Redisson 自动配置，骨架上下文测试不依赖外部 Redis。
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
