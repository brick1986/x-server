package io.github.brick.data.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.EntityPriorities;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.lock.RedissonLockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * game-data 自动装配：Redis 池 100~200、Mongo 池 50~100（架构 spec §3.1）。
 * 自定义 {@link RedissonClient}（@ConditionalOnMissingBean，使 redisson-spring-boot-starter 的
 * RedissonAutoConfigurationV4 退避），统一池大小与地址。
 *
 * <p><b>Redis 连接配置只认 {@code game.data.redisAddress}。</b>本类自行 {@code Redisson.create(Config)}
 * 完全绕开 redisson-spring-boot-starter，因此 {@code spring.data.redis.*} 一族属性在本模块**静默无效**
 * ——配了不报错也不生效。改地址/池大小请改 {@code game.data.*}（见 {@link DataProperties}）。
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
        SingleServerConfig single = c.useSingleServer()
                .setAddress(p.getRedisAddress())
                .setConnectionPoolSize(p.getRedisPoolSize());
        // 仅在非空时设置：Redisson 把空串也当作「有密码」而发 AUTH，连无鉴权实例会失败
        if (StringUtils.hasText(p.getRedisPassword())) {
            single.setPassword(p.getRedisPassword());
        }
        if (StringUtils.hasText(p.getRedisUsername())) {
            single.setUsername(p.getRedisUsername());
        }
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

    /**
     * 仅当业务模块登记了 {@link EntityPriorities} 才装配——实体名属业务词汇，game-data 不内置
     * （primitives §6）。不加锁的模块（game-dbserver）不提供该 bean，此处不装配，其余原语照常可用。
     */
    @Bean
    @ConditionalOnBean(EntityPriorities.class)
    LockScope lockScope(RedissonClient c, RedisStore rs, MongoStore ms, CommitLua cl,
                        JsonCodec jc, DataProperties p, EntityPriorities priorities) {
        return new RedissonLockScope(c, rs, ms, cl, jc,
                p.getLockWaitMillis(), p.getLockLeaseSeconds(),
                priorities.byEntity());
    }
}
