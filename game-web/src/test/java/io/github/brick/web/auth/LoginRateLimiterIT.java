package io.github.brick.web.auth;
import io.github.brick.data.LocalRedisMongo;
import io.github.brick.web.config.WebProperties;
import io.github.brick.web.api.GameException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginRateLimiterIT extends LocalRedisMongo {
    @Test void limitsAreIndependentAndDoNotExtendTheWindow() throws Exception {
        var p = new WebProperties(); p.setAuthIpLimit(2); p.setAuthAccountLimit(1); p.setAuthWindowSeconds(1);
        var limiter = new LoginRateLimiter(redis,p);
        limiter.checkIp("127.0.0.1"); limiter.checkIp("127.0.0.1");
        assertThatThrownBy(() -> limiter.checkIp("127.0.0.1")).isInstanceOfSatisfying(GameException.class,e -> assertThat(e.status()).isEqualTo(429));
        limiter.checkIp("127.0.0.2");
        limiter.checkAccount("alice");
        assertThatThrownBy(() -> limiter.checkAccount("alice")).isInstanceOf(GameException.class);
        limiter.checkAccount("bob");
        Thread.sleep(1100);
        limiter.checkIp("127.0.0.1"); limiter.checkAccount("alice");
    }
}
