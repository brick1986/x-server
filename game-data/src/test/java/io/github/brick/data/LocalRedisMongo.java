package io.github.brick.data;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 集成测试基类：连本地预起的 Redis(6379)/Mongo(27017)，每用例 flush（primitives §7）。
 * 不引 Testcontainers。
 */
public abstract class LocalRedisMongo {

    protected static final String MONGO_DB = "game_test";

    /** 可选 Redis 鉴权密码；非空时调用 setPassword，空则连无鉴权 Redis（CI/Memurai 默认）。 */
    private static final String REDIS_TEST_PASSWORD = System.getenv("REDIS_TEST_PASSWORD");

    protected static RedissonClient redis;
    protected static MongoClient mongo;

    @BeforeAll
    static void startClients() {
        Config cfg = new Config();
        cfg.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(8);
        if (REDIS_TEST_PASSWORD != null && !REDIS_TEST_PASSWORD.isEmpty()) {
            cfg.setPassword(REDIS_TEST_PASSWORD);
        }
        redis = Redisson.create(cfg);
        mongo = MongoClients.create("mongodb://localhost:27017");
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
