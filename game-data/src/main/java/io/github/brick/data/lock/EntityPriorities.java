package io.github.brick.data.lock;

import java.util.Map;

/**
 * 实体类型加锁优先级表，由**业务模块**提供（primitives §6：game-data 不认识业务实体）。
 *
 * <p>加锁顺序是死锁防护的正确性关键：多锁场景下 {@link LockScope#lockAll} 按本表升序 acquire，
 * 全局统一顺序才能保证两个并发请求不会以相反顺序抢同两把锁而互等（并发修订 §1.3）。
 * 故实现须**稳定**——同一进程内每次返回相同映射，不得随请求变化。
 *
 * <p>game-data 自身不提供任何默认实现：实体名（{@code guild}/{@code player}/…）属业务词汇，
 * 内置默认值会让「不认识业务实体」这条架构约束名存实亡，且新增实体要改动底层模块。
 * 业务模块以 {@code @Bean} 登记，新增实体只改业务侧：
 *
 * <pre>{@code
 * @Bean
 * EntityPriorities entityPriorities() {
 *     return () -> Map.of("guild", 0, "player", 1);
 * }
 * }</pre>
 *
 * <p>未登记的实体类型在 {@code lockAll} 时抛 {@link IllegalArgumentException}，强制登记。
 * 不加锁的模块（如 game-dbserver 只消费 dirty 集合落盘）无需提供本 bean——届时不装配
 * {@link LockScope}，其余数据原语照常可用。
 */
@FunctionalInterface
public interface EntityPriorities {

	/** entity → 优先级；数值小者先加锁。 */
	Map<String, Integer> byEntity();
}
