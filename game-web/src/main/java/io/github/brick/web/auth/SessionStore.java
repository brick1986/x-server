package io.github.brick.web.auth;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.web.api.GameException;
import io.github.brick.web.config.WebProperties;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class SessionStore {
    private final RedissonClient redis;
    private final JsonCodec codec;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration ttl;
    public SessionStore(RedissonClient redis, JsonCodec codec, SecureRandom random, Clock clock, WebProperties p) {
        this.redis=redis; this.codec=codec; this.random=random; this.clock=clock; this.ttl=Duration.ofSeconds(p.getSessionTtlSeconds());
    }
    public record IssuedSession(String token, SessionIdentity identity) {
        @Override public String toString() { return "IssuedSession[token=REDACTED, identity="+identity+"]"; }
    }
    public IssuedSession issue(long accountId, long uid) {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), digest=digest(token);
        var identity=new SessionIdentity(accountId,uid,clock.millis()+ttl.toMillis(),digest);
        bucket("auth:session:"+digest).set(codec.encode(identity),ttl);
        bucket("auth:current:"+accountId).set(digest,ttl);
        return new IssuedSession(token,identity);
    }
    public SessionIdentity authenticate(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw GameException.unauthorized();
        String digest=digest(token), json=bucket("auth:session:"+digest).get();
        if (json==null) throw GameException.unauthorized();
        SessionIdentity identity=codec.decode(json,SessionIdentity.class);
        if (identity.accountId()<=0 || identity.uid()<=0 || identity.expiresAt()<=clock.millis()
                || !digest.equals(identity.tokenDigest()) || !digest.equals(bucket("auth:current:"+identity.accountId()).get()))
            throw GameException.unauthorized();
        return identity;
    }
    public void revoke(SessionIdentity identity) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",
            RScript.ReturnType.LONG,List.of("auth:current:"+identity.accountId()),identity.tokenDigest());
        bucket("auth:session:"+identity.tokenDigest()).delete();
    }
    private RBucket<String> bucket(String key) { return redis.getBucket(key,StringCodec.INSTANCE); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
