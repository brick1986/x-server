package io.github.brick.data.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.lock.RedissonLockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * game-data 自动装配：Redis 池 100~200、Mongo 池 50~100（架构 spec §3.1）。
 * 自定义 {@link RedissonClient}（@ConditionalOnMissingBean，使 redisson-spring-boot-starter 的
 * RedissonAutoConfigurationV4 退避），统一池大小与地址。
 *
 * <p>LockScope 透传 waitMillis（毫秒）与 leaseSeconds（秒）原值——RedissonLockScope.lockAll 内部
 * 调用 {@code tryLock(waitMillis, leaseSeconds*1000L, MILLISECONDS)} 自行折算（架构 §4.4）。
 */
@AutoConfiguration
@AutoConfigureBefore(name = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@EnableConfigurationProperties(DataProperties.class)
public class DataAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    RedissonClient redissonClient(DataProperties p) {
        Config c = new Config();
        // setLazyInitialization(true)：Redisson.create 不在构造期 cm.connect()（ConnectionManager.create
        // 仅在 !lazyInitialization 时调用 connect），首次操作才建连——契合 primitives §3.4「懒连接」设计意图。
        // 池容量仍由 connectionPoolSize（100~200）兜底，首次操作后按默认 minIdleSize 预热。
        c.setLazyInitialization(true);
        c.useSingleServer().setAddress(p.getRedisAddress())
                .setConnectionPoolSize(p.getRedisPoolSize());
        return Redisson.create(c);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    MongoClient mongoClient(DataProperties p) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(p.getMongoUri()))
                .applyToConnectionPoolSettings(b -> b
                        .maxSize(p.getMongoPoolSize())
                        .minSize(Math.max(1, p.getMongoPoolSize() / 4)))
                .build());
    }

    @Bean
    JsonCodec jsonCodec() {
        return new JsonCodec();
    }

    @Bean
    RedisStore redisStore(RedissonClient c) {
        return new RedisStore(c);
    }

    @Bean
    MongoStore mongoStore(MongoClient c, DataProperties p) {
        return new MongoStore(c, p.getMongoDb());
    }

    @Bean
    CommitLua commitLua(RedissonClient c) {
        return new CommitLua(c);
    }

    @Bean
    DirtyLedger dirtyLedger(RedissonClient c) {
        return new DirtyLedger(c);
    }

    @Bean
    LockScope lockScope(RedissonClient c, RedisStore rs, MongoStore ms, CommitLua cl,
                        JsonCodec jc, DataProperties p) {
        return new RedissonLockScope(c, rs, ms, cl, jc,
                p.getLockWaitMillis(), p.getLockLeaseSeconds(),
                Map.of("guild", 0, "player", 1));   // 菜鸟期全局类型优先级
    }
}
