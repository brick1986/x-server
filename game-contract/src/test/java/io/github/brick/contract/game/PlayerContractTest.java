package io.github.brick.contract.game;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayerContractTest {
    @Test void profileAndRenameRoundTrip() throws Exception {
        var profile = PlayerProfileMessage.newBuilder().setUid(4294967298L).setAccountId(4294967297L)
            .setDisplayName("玩家😀").setCreatedAt(100).setUpdatedAt(200).build();
        var response = ProfileResponse.newBuilder().setProfile(profile).build();
        assertEquals(response, ProfileResponse.parseFrom(response.toByteArray()));
        var rename = RenameRequest.newBuilder().setDisplayName("玩家😀").build();
        assertEquals(rename, RenameRequest.parseFrom(rename.toByteArray()));
        assertEquals(ProfileRequest.getDefaultInstance(), ProfileRequest.parseFrom(new byte[0]));
        var error = ErrorResponse.newBuilder().setCode("UNAUTHORIZED").setMessage("请登录").setRequestId("req").build();
        assertEquals(error, ErrorResponse.parseFrom(error.toByteArray()));
    }
}
