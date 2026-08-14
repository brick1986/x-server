package io.github.brick.data.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataKeysTest {

	@Test
	void playerProfileKey() {
		assertEquals("player:7:profile", DataKeys.playerProfile(7));
	}

	@Test
	void playerBagKey() {
		assertEquals("player:7:bag", DataKeys.playerBag(7));
	}

	@Test
	void lockKeyFollowsEntityIdFormat() {
		assertEquals("lock:player:7", DataKeys.lockKey("player", 7));
		assertEquals("lock:guild:3", DataKeys.lockKey("guild", 3));
	}

	@Test
	void keyComposesEntityIdField() {
		assertEquals("player:123:profile", DataKeys.key("player", 123, "profile"));
		assertEquals("guild:7:fund", DataKeys.key("guild", 7, "fund"));
	}

	@Test
	void entityOfParsesFirstSegment() {
		assertEquals("player", DataKeys.entityOf("player:123:profile"));
		assertEquals("guild", DataKeys.entityOf("guild:7:fund"));
	}

	@Test
	void idOfParsesSecondSegmentAsLong() {
		assertEquals(123L, DataKeys.idOf("player:123:profile"));
		assertEquals(7L, DataKeys.idOf("guild:7:fund"));
	}

	@Test
	void fieldOfParsesThirdSegment() {
		assertEquals("profile", DataKeys.fieldOf("player:123:profile"));
		assertEquals("fund", DataKeys.fieldOf("guild:7:fund"));
	}

	@Test
	void collectionOfIsEntityColonField() {
		assertEquals("player:profile", DataKeys.collectionOf("player:123:profile"));
		assertEquals("player:bag", DataKeys.collectionOf("player:123:bag"));
		assertEquals("guild:fund", DataKeys.collectionOf("guild:7:fund"));
	}

	@Test
	void docIdOfIsId() {
		assertEquals(123L, DataKeys.docIdOf("player:123:profile"));
	}

	@Test
	void parseRejectsMalformedKey() {
		assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("no-colons"));
		assertThrows(IllegalArgumentException.class, () -> DataKeys.idOf("player:notnum:profile"));
	}
}
