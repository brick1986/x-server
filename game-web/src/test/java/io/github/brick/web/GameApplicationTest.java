package io.github.brick.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 排除 Redisson 自动配置，使骨架上下文测试不依赖外部 Redis（Plan B/C 集成测试再连真实 Redis）。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4")
class GameApplicationTest {

	@Test
	void contextLoads() {
	}
}
