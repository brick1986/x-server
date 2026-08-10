package io.github.brick.contract.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginContractTest {

	@Test
	void loginRequestGeneratedAndRoundTrips() {
		LoginRequest req = LoginRequest.newBuilder().setToken("abc").build();
		assertEquals("abc", req.getToken());
	}

	@Test
	void loginResponseGeneratedAndRoundTrips() {
		LoginResponse resp = LoginResponse.newBuilder().setOk(true).setUserId(42L).build();
		assertTrue(resp.getOk());
		assertEquals(42L, resp.getUserId());
	}
}
