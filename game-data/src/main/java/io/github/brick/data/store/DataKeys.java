package io.github.brick.data.store;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据 key 命名常量 + 解析。key 约定钉死为 {@code {bNNNN}:{entity}:{id}:{field}}（如
 * {@code {b0421}:player:123:profile}），锁名 {@code lock:{entity}:{id}}（架构 spec §4.1/§4.2、
 * 并发修订 §1.1，primitives §3.1）。本类是该约定的唯一来源，业务域不得自造违反约定的 key。
 *
 * <p><b>桶前缀 {@code {bNNNN}} 是 Redis Cluster 的 hash tag。</b>slot 只由 tag 决定，同号键
 * 必然同 slot——{@code CommitLua} 的「SET 数据 key + SADD 同桶脏集合」靠它保住原子性在
 * Cluster 下合法（提交桶化设计 §2/§3）。桶号由 {@link #bucket(String, long)} 从逻辑身份整串
 * 散列得出；{@link #BUCKETS} 是协议常量，**改它等于全量数据重写**（提交桶化设计 §3.3）。
 *
 * <p>只提供**与实体无关**的组装与解析。不含 {@code playerProfile()} 一类便捷方法——
 * {@code player}/{@code profile} 属业务词汇，放这里会让「game-data 不认识业务实体」
 * （primitives §6）失守。业务侧用 {@link #key(String, long, String)} 或自建常量类。
 */
public final class DataKeys {

	/** 桶数（协议常量，一次性钉死）。改 K = 全量数据重写，选取依据见提交桶化设计 §3.3。 */
	public static final int BUCKETS = 4096;

	/** 桶前缀文法：{b + 恰好 4 位数字 + }:（定宽，桶号必须小于 BUCKETS）。 */
	private static final Pattern TAG = Pattern.compile("^\\{b(\\d{4})\\}:");

	private DataKeys() {
	}

	/** 数据 key：{@code {bNNNN}:{entity}:{id}:{field}}。field 不得含 {@code :}。 */
	public static String key(String entity, long id, String field) {
		return tag(bucket(entity, id)) + entity + ":" + id + ":" + field;
	}

	/** 分布式锁名：{@code lock:{entity}:{id}}。锁是单 key 操作，无需 hash tag。 */
	public static String lockKey(String entity, long id) {
		return "lock:" + entity + ":" + id;
	}

	/**
	 * 桶号：对 {@code entity:id} 整串散列后取模。散列对象必须是**整串**——朴素
	 * {@code id % K} 遇上结构化 id（按服务器分块分配、snowflake 机器位）会把数据
	 * 系统性偏到少数桶里（提交桶化设计 §3.2）。高 16 位异或进低位，防低位聚集。
	 */
	static int bucket(String entity, long id) {
		int h = (entity + ":" + id).hashCode();
		h ^= h >>> 16;
		return Math.floorMod(h, BUCKETS);
	}

	/** 由数据 key 解析桶号。CommitLua/DirtyLedger 由此从 key 推导同桶集合名。 */
	public static int bucketOf(String key) {
		Matcher m = tagMatcher(key);
		return checkRange(key, m);
	}

	private static List<String> parts(String key) {
		Matcher m = tagMatcher(key);
		checkRange(key, m);
		String[] s = key.substring(m.end()).split(":", -1);
		if (s.length != 3 || s[0].isEmpty() || s[1].isEmpty() || s[2].isEmpty()) {
			throw new IllegalArgumentException("非法 key（应为 {bNNNN}:{entity}:{id}:{field}）: " + key);
		}
		return List.of(s[0], s[1], s[2]);
	}

	private static Matcher tagMatcher(String key) {
		Matcher m = TAG.matcher(key);
		if (!m.find()) {
			throw new IllegalArgumentException("非法 key（应为 {bNNNN}:{entity}:{id}:{field}）: " + key);
		}
		return m;
	}

	private static int checkRange(String key, Matcher m) {
		int bucket = Integer.parseInt(m.group(1));
		if (bucket >= BUCKETS) {
			throw new IllegalArgumentException("非法 key（桶号越界 ≥" + BUCKETS + "）: " + key);
		}
		return bucket;
	}

	private static String tag(int bucket) {
		return String.format("{b%04d}:", bucket);
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
