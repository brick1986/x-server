package io.github.brick.web.config;

import io.github.brick.data.lock.EntityPriorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 本服务的实体加锁优先级登记表。业务词汇留在业务模块——game-data 不认识业务实体
 * （primitives §6），故优先级表由此处提供，而非内置于底层模块。
 *
 * <p>多锁场景按本表升序 acquire，全局统一顺序防止两个并发请求以相反顺序抢同两把锁而互等
 * （并发修订 §1.3）。菜鸟期两类实体：{@code guild} 先于 {@code player}。
 *
 * <p><b>新增实体类型必须在此登记</b>，否则 {@code lockAll} 抛 {@link IllegalArgumentException}
 * ——刻意 fail-fast，逼使新实体明确自己在全局加锁序中的位置，而不是随意插入导致死锁。
 * 登记时想清楚：与已有实体的相对顺序一旦定下，全服务共用。
 */
@Configuration
public class LockPriorityConfig {

	@Bean
	EntityPriorities entityPriorities() {
		return () -> Map.of(
				"guild", 0,
				"player", 1);
	}
}
