package io.github.brick.data.store;

/**
 * 数据 key 命名常量 + entity→锁名映射。
 * 锁名格式遵循架构 spec §3.1：{@code lock:{entity}:{id}}。
 * 本类为骨架种子，后续 Plan B 的 RedisStore/MongoStore/LockManager 将消费并按需扩展。
 */
public final class DataKeys {

	private DataKeys() {
	}

	/** 玩家主档 key：{@code player:{id}:profile}。 */
	public static String playerProfile(long playerId) {
		return "player:" + playerId + ":profile";
	}

	/** 玩家背包 key：{@code player:{id}:bag}。 */
	public static String playerBag(long playerId) {
		return "player:" + playerId + ":bag";
	}

	/** 分布式锁名：{@code lock:{entity}:{id}}（架构 spec §3.1）。 */
	public static String lockKey(String entity, long id) {
		return "lock:" + entity + ":" + id;
	}
}
