package io.github.brick.web.auth;
import io.github.brick.web.api.GameException;
import io.github.brick.web.config.WebProperties;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import java.util.List;

public final class LoginRateLimiter {
    private final RedissonClient redis;
    private final WebProperties p;
    public LoginRateLimiter(RedissonClient redis, WebProperties p) { this.redis=redis; this.p=p; }
    public void checkIp(String remoteAddress) { check("ip",remoteAddress,p.getAuthIpLimit()); }
    public void checkAccount(String normalizedUsername) { check("account",normalizedUsername,p.getAuthAccountLimit()); }
    private void check(String category, String value, int limit) {
        long count=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",
            RScript.ReturnType.LONG,List.of("auth:limit:"+category+":"+SessionStore.digest(value)),Long.toString(p.getAuthWindowSeconds()));
        if (count>limit) throw new GameException(429,"RATE_LIMITED","请求过于频繁，请稍后再试");
    }
}
