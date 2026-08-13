package io.github.brick.data.store;

import java.util.List;

/**
 * 数据 key 命名常量 + 解析。key 约定钉死为 {@code {entity}:{id}:{field}}（如
 * {@code player:123:profile}），锁名 {@code lock:{entity}:{id}}（架构 spec §3.1、§4.1，
 * 并发修订 §1.1，primitives §3.1）。本类是该约定的唯一来源，业务域不得自造违反约定的 key。
 */
public final class DataKeys {

	private DataKeys() {
	}

	/** 数据 key：{@code {entity}:{id}:{field}}。field 不得含 {@code :}。 */
	public static String key(String entity, long id, String field) {
		return entity + ":" + id + ":" + field;
	}

	public static String playerProfile(long playerId) {
		return key("player", playerId, "profile");
	}

	public static String playerBag(long playerId) {
		return key("player", playerId, "bag");
	}

	/** 分布式锁名：{@code lock:{entity}:{id}}。 */
	public static String lockKey(String entity, long id) {
		return "lock:" + entity + ":" + id;
	}

	private static List<String> parts(String key) {
		String[] s = key.split(":", -1);
		if (s.length != 3 || s[0].isEmpty() || s[1].isEmpty() || s[2].isEmpty()) {
			throw new IllegalArgumentException("非法 key（应为 {entity}:{id}:{field}）: " + key);
		}
		return List.of(s[0], s[1], s[2]);
	}

	public static String entityOf(String key) {
		return parts(key).get(0);
	}

	public static long idOf(String key) {
		try {
			return Long.parseLong(parts(key).get(1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("非法 key（id 非数字）: " + key, e);
		}
	}

	public static String fieldOf(String key) {
		return parts(key).get(2);
	}

	/** Mongo 集合名 = {@code {entity}:{field}}（Mongo 映射决策 A）。 */
	public static String collectionOf(String key) {
		return entityOf(key) + ":" + fieldOf(key);
	}

	/** Mongo 文档 _id = {id}（Mongo 映射决策 A）。 */
	public static long docIdOf(String key) {
		return idOf(key);
	}
}
