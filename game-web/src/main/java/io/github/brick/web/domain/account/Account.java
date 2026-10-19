package io.github.brick.web.domain.account;

public record Account(long accountId, String username, String passwordHash, long playerId,
                      boolean playerInitialized, long createdAt) {
    @Override
    public String toString() {
        return "Account[id=" + accountId + ", credentials=REDACTED]";
    }
}
