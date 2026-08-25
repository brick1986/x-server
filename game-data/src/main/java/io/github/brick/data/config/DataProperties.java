package io.github.brick.data.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * game-data 运行时配置（primitives §3.4 连接池 + 决策 2 租约）。
 *
 * <p>池大小显式设限：Redis 100~200、Mongo 50~100（架构 spec §3.1）。范围由 {@link Min}/{@link Max}
 * 在启动期强制——「禁止无上限」若只写在注释里，一行越界的 yml 就能让它失守。越界值使上下文启动失败，
 * 而非留到线上耗尽连接才暴露。
 *
 * <p>Redis 连接只认本类的 {@code game.data.*}；{@code spring.data.redis.*} 静默无效
 * （见 {@link DataAutoConfiguration}）。
 */
@ConfigurationProperties(prefix = "game.data")
@Validated
public class DataProperties {

    @NotBlank
    private String redisAddress = "redis://127.0.0.1:6379";

    /**
     * Redis 鉴权密码；空/null 表示连无鉴权实例。
     *
     * <p>独立于 {@link #redisAddress} 而非拼进连接串（{@code redis://:pwd@host}）：连接串会随
     * 启动日志、连接异常栈一起被打印，密码就此泄漏到日志里。独立字段可由环境变量注入
     * （{@code ${REDIS_PASSWORD:}}），不进 git、不随地址一起被打印。
     *
     * <p><b>此处不设默认密码。</b>默认值会进 git，且会在部署时忘设环境变量的情况下静默充当
     * 线上兜底——失败尚可察觉，连上才是灾难。本地开发的密码写在
     * {@code game-data/src/test/resources/application.yaml}（仅测试用，不打进 jar）。
     */
    private String redisPassword;

    /** Redis 6+ ACL 用户名；空表示用 default 用户（仅密码鉴权）。 */
    private String redisUsername;

    @Min(100) @Max(200)
    private int redisPoolSize = 100;

    @NotBlank
    private String mongoUri = "mongodb://localhost:27017";

    @NotBlank
    private String mongoDb = "game";

    @Min(50) @Max(100)
    private int mongoPoolSize = 50;

    /** fail-fast 等待（决策 2）：锁不可用时写操作快速失败，不无限等待（架构 §4.4）。 */
    @Min(1)
    private long lockWaitMillis = 2000L;

    /** 固定租约、无看门狗（并发修订 §4.1）。 */
    @Min(1)
    private long lockLeaseSeconds = 10L;

    public String getRedisAddress() { return redisAddress; }
    public void setRedisAddress(String v) { this.redisAddress = v; }
    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String v) { this.redisPassword = v; }
    public String getRedisUsername() { return redisUsername; }
    public void setRedisUsername(String v) { this.redisUsername = v; }
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
