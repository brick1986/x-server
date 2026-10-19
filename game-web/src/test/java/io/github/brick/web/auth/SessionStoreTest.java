package io.github.brick.web.auth;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;

import java.security.SecureRandom;
import java.time.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SessionStoreTest {
    @Test
    void failedCurrentWriteDoesNotReturnASession() {
        RedissonClient redis = mock(RedissonClient.class);
        RBucket<String> stored = mock(RBucket.class), current = mock(RBucket.class);
        when(redis.<String>getBucket(startsWith("auth:session:"), any())).thenReturn(stored);
        when(redis.<String>getBucket(eq("auth:current:1"), any())).thenReturn(current);
        doThrow(new IllegalStateException("backend unavailable")).when(current).set(anyString(), any(Duration.class));
        var sessions = new SessionStore(redis, new JsonCodec(), new SecureRandom(), Clock.systemUTC(), new WebProperties());
        assertThatThrownBy(() -> sessions.issue(1, 11)).isInstanceOf(IllegalStateException.class);
        verify(stored).set(anyString(), any(Duration.class));
    }
}
