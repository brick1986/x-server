package io.github.brick.dbserver.config;

import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import io.github.brick.dbserver.flush.FlushOrchestrator;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 落盘编排装配。沿用 {@code DataAutoConfiguration} 的**显式 @Bean** 风格，不用 @Component 扫描
 * ——构造依赖一眼可见，且落盘各组件的协作顺序（编排 ← 调度 ← 停机）在这里读得出来。
 *
 * <p>{@link DirtyLedger}/{@link RedisStore}/{@link MongoStore} 由 {@code game-data} 的
 * 自动装配提供；本模块不登记 {@code EntityPriorities}，故 {@code LockScope} 不装配——落盘不加实体锁。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DbServerProperties.class)
public class DbServerConfiguration {

    @Bean
    FlushOrchestrator flushOrchestrator(RedissonClient redisson, DirtyLedger dirty,
                                        RedisStore redis, MongoStore mongo, DbServerProperties p) {
        return new FlushOrchestrator(redisson, dirty, redis, mongo,
                p.getChunkSize(), p.getLockLeaseSeconds());
    }
}
