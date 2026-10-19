package io.github.brick.contract.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginContractTest {

    @Test
    void loginRequestGeneratedAndRoundTrips() throws Exception {
        LoginRequest req = LoginRequest.newBuilder().setUsername("alice").setPassword("long password 123").build();
        assertEquals(req, LoginRequest.parseFrom(req.toByteArray()));
        org.junit.jupiter.api.Assertions.assertNull(LoginRequest.getDescriptor().findFieldByNumber(1));
        var register = RegisterRequest.newBuilder().setUsername("alice").setPassword("long password 123").build();
        assertEquals(register, RegisterRequest.parseFrom(register.toByteArray()));
        var registered = RegisterResponse.newBuilder().setAccountId(4294967297L).build();
        assertEquals(4294967297L, RegisterResponse.parseFrom(registered.toByteArray()).getAccountId());
    }

    @Test
    void loginResponseGeneratedAndRoundTrips() throws Exception {
        LoginResponse resp = LoginResponse.newBuilder().setOk(true).setUserId(4294967298L).setToken("opaque").setAccountId(4294967297L).setExpiresAt(1800000000000L).build();
        assertTrue(resp.getOk());
        assertEquals(resp, LoginResponse.parseFrom(resp.toByteArray()));
        assertEquals(1, LoginResponse.getDescriptor().findFieldByName("ok").getNumber());
        assertEquals(2, LoginResponse.getDescriptor().findFieldByName("user_id").getNumber());
        assertEquals(LogoutRequest.getDefaultInstance(), LogoutRequest.parseFrom(new byte[0]));
        var logout = LogoutResponse.newBuilder().setOk(true).build();
        assertTrue(LogoutResponse.parseFrom(logout.toByteArray()).getOk());
    }
}
