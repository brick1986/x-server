package io.github.brick.data;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

/**
 * 集成测试基类：连本地预起的 Redis/Mongo，每用例 flush（primitives §7）。不引 Testcontainers。
 *
 * <p>连接参数读 {@code src/test/resources/application.yaml} 的 {@code game.data.*}——换端口、
 * 改密码只改那一个文件，不必设环境变量，更不必去改生产类 {@code DataProperties} 的默认值
 * （那会把本机配置和密码提交进 git，并在部署时充当线上兜底）。
 *
 * <p>该文件含本机密码，在 {@code .gitignore} 中，新 clone 的仓库里没有；
 * 复制同目录的 {@code application.yaml.example} 即可（缺失时本类会给出该提示）。
 *
 * <p>本类是纯 JUnit、不启动 Spring 上下文（IT 保持轻量），故用 {@link YamlPropertySourceLoader}
 * 直接解析 yaml，而非依赖 {@code DataProperties} 的绑定机制。环境变量 {@code REDIS_TEST_PASSWORD}
 * 优先于 yaml，供 CI 注入而无需改文件。
 */
public abstract class LocalRedisMongo {

	private static final String CONFIG_FILE = "application.yaml";

	private static final PropertySource<?> PROPS = loadTestYaml();

	protected static final String MONGO_DB = prop("game.data.mongo-db", "game_test");

	protected static RedissonClient redis;
	protected static MongoClient mongo;

	private static PropertySource<?> loadTestYaml() {
		ClassPathResource resource = new ClassPathResource(CONFIG_FILE);
		if (!resource.exists()) {
			// 该文件含本机密码，故在 .gitignore 中——新 clone 的仓库里不存在。
			// 直接抛 FileNotFoundException 只会给出「资源不存在」，看不出该怎么办，故在此明示。
			throw new IllegalStateException("""
					缺少集成测试配置 game-data/src/test/resources/%s。
					该文件含本机 Redis 密码，不入库（见 .gitignore）。请复制模板并改成你本机的值：
					    cp game-data/src/test/resources/%s.example game-data/src/test/resources/%s
					详见 DEVELOPMENT.md「跑测试」一节。"""
					.formatted(CONFIG_FILE, CONFIG_FILE, CONFIG_FILE));
		}
		try {
			List<PropertySource<?>> sources = new YamlPropertySourceLoader()
					.load("test-config", resource);
			return sources.isEmpty() ? null : sources.get(0);
		} catch (IOException e) {
			throw new IllegalStateException("读取测试配置 " + CONFIG_FILE + " 失败", e);
		}
	}

	private static String prop(String key, String fallback) {
		Object v = PROPS == null ? null : PROPS.getProperty(key);
		return v == null ? fallback : v.toString();
	}

	@BeforeAll
	static void startClients() {
		redis = newClient(8);
		mongo = MongoClients.create(prop("game.data.mongo-uri", "mongodb://localhost:27017"));
	}

	/**
	 * 按测试 yaml 的连接参数另建一个独立客户端，供需要「第二个连接」的用例使用
	 * （如 all-or-nothing 用例要另一客户端占住锁）。调用方负责 shutdown。
	 */
	protected static RedissonClient newClient() {
		return newClient(4);
	}

	private static RedissonClient newClient(int poolSize) {
		Config cfg = new Config();
		SingleServerConfig single = cfg.useSingleServer()
				.setAddress(prop("game.data.redis-address", "redis://127.0.0.1:6379"))
				.setConnectionPoolSize(poolSize)
				.setConnectionMinimumIdleSize(1);

		// CI 用环境变量注入；未设则用 yaml 里的值
		String envPwd = System.getenv("REDIS_TEST_PASSWORD");
		String password = (envPwd != null && !envPwd.isEmpty())
				? envPwd
				: prop("game.data.redis-password", "");
		// 空串不能传给 Redisson——它会当作「有密码」照样发 AUTH，连无鉴权实例反而失败
		if (!password.isEmpty()) {
			single.setPassword(password);
		}
		String username = prop("game.data.redis-username", "");
		if (!username.isEmpty()) {
			single.setUsername(username);
		}
		return Redisson.create(cfg);
	}

	@AfterAll
	static void stopClients() {
		if (redis != null) redis.shutdown();
		if (mongo != null) mongo.close();
	}

	@BeforeEach
	void flushAll() {
		redis.getKeys().flushdb();
		mongo.getDatabase(MONGO_DB).drop();
	}

	/** 当前 Mongo 测试库。 */
	protected com.mongodb.client.MongoDatabase db() {
		return mongo.getDatabase(MONGO_DB);
	}
}
