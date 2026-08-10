package io.github.brick.data.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
