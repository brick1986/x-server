package io.github.brick.web.auth;
public record SessionIdentity(long accountId, long uid, long expiresAt, String tokenDigest) {
    @Override public String toString() { return "SessionIdentity[accountId=" + accountId + ", uid=" + uid + "]"; }
}
