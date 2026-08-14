package io.github.brick.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * game-data 运行时配置（primitives §3.4 连接池 + 决策 2 租约）。
 * 池大小显式设限：Redis 100~200、Mongo 50~100（架构 spec §3.1）。
 */
@ConfigurationProperties(prefix = "game.data")
public class DataProperties {

    private String redisAddress = "redis://127.0.0.1:6379";
    private int redisPoolSize = 100;            // 100~200
    private String mongoUri = "mongodb://localhost:27017";
    private String mongoDb = "game";
    private int mongoPoolSize = 50;              // 50~100
    private long lockWaitMillis = 2000L;         // fail-fast 等待（决策 2）
    private long lockLeaseSeconds = 10L;         // 固定租约无看门狗

    public String getRedisAddress() { return redisAddress; }
    public void setRedisAddress(String v) { this.redisAddress = v; }
    public int getRedisPoolSize() { return redisPoolSize; }
    public void setRedisPoolSize(int v) { this.redisPoolSize = v; }
    public String getMongoUri() { return mongoUri; }
    public void setMongoUri(String v) { this.mongoUri = v; }
    public String getMongoDb() { return mongoDb; }
    public void setMongoDb(String v) { this.mongoDb = v; }
    public int getMongoPoolSize() { return mongoPoolSize; }
    public void setMongoPoolSize(int v) { this.mongoPoolSize = v; }
    public long getLockWaitMillis() { return lockWaitMillis; }
    public void setLockWaitMillis(long v) { this.lockWaitMillis = v; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long v) { this.lockLeaseSeconds = v; }
}
