package io.github.brick.data.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataKeysTest {

	@Test
	void lockKeyFollowsEntityIdFormat() {
		assertEquals("lock:player:7", DataKeys.lockKey("player", 7));
	}

	@Test
	void keyCarriesBucketPrefix() {
		// 桶号由散列决定，只断言形状；具体桶号由分布测试（Task 3）管
		String key = DataKeys.key("player", 123, "profile");
		assertTrue(key.matches("\\{b\\d{4}\\}:player:123:profile"), key);
	}

	@Test
	void bucketOfReadsThePrefixBack() {
		String key = DataKeys.key("guild", 7, "fund");
		assertEquals(String.format("{b%04d}", DataKeys.bucketOf(key)), key.substring(0, 7));
	}

	@Test
	void entityIdFieldRoundTrip() {
		String key = DataKeys.key("player", 123, "bag");
		assertEquals("player", DataKeys.entityOf(key));
		assertEquals(123L, DataKeys.idOf(key));
		assertEquals("bag", DataKeys.fieldOf(key));
	}

	@Test
	void collectionAndDocIdUnchanged() {
		String key = DataKeys.key("player", 123, "profile");
		assertEquals("player:profile", DataKeys.collectionOf(key));
		assertEquals(123L, DataKeys.docIdOf(key));
	}

	@Test
	void bucketIsStableAndSharedByFieldsOfOneInstance() {
		assertEquals(DataKeys.bucket("player", 5), DataKeys.bucket("player", 5));
		// 同实例的全部 field 落同一桶（设计 §3.2）
		assertEquals(DataKeys.bucketOf(DataKeys.key("player", 5, "bag")),
				DataKeys.bucketOf(DataKeys.key("player", 5, "profile")));
	}

	@Test
	void parseRejectsOldFormatKey() {
		// 迁移依赖此行为：旧格式 key 一律拒绝（设计 §3.4、§7）
		assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("player:123:profile"));
		assertThrows(IllegalArgumentException.class, () -> DataKeys.bucketOf("player:123:profile"));
	}

	@Test
	void parseRejectsMalformedOrOutOfRangeBucket() {
		assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("no-colons"));
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.idOf("{b0041}:player:notnum:profile"));
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("{b9999}:player:1:profile"));   // ≥ BUCKETS
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("{bx041}:player:1:profile"));   // 非数字
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("b0041:player:1:profile"));     // 缺花括号
	}
}
