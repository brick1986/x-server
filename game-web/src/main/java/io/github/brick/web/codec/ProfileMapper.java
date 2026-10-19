package io.github.brick.web.codec;

import io.github.brick.contract.game.PlayerProfileMessage;
import io.github.brick.web.domain.player.PlayerProfile;

public final class ProfileMapper {
    private ProfileMapper() {
    }

    public static PlayerProfileMessage toMessage(PlayerProfile p) {
        return PlayerProfileMessage.newBuilder().setUid(p.uid()).setAccountId(p.accountId()).setDisplayName(p.displayName()).setCreatedAt(p.createdAt()).setUpdatedAt(p.updatedAt()).build();
    }
}
