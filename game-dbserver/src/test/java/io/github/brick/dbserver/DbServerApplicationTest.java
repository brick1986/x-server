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
		"game.dbserver.flush-interval-millis=60000"
})
class DbServerApplicationTest {

	@Test
	void contextLoads() {
	}
}
