package io.github.brick.web;

import io.github.brick.web.support.WebIntegrationSupport;
import io.github.brick.contract.game.*;
import io.github.brick.data.store.*;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.web.log.*;
import io.github.brick.web.auth.SessionStore;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

class BusinessLoopIT extends WebIntegrationSupport {
    @Test
    void registeredPlayerUpdatesFlushAutomaticallyAndColdReadsRestoreIt() throws Exception {
        startFlusher();
        register("alice");
        var login = login("alice");
        assertThat(login.getOk()).isTrue();
        assertThat(login.getUserId()).isPositive();
        assertThat(profile(login.getToken()).getUid()).isEqualTo(login.getUserId());
        var rename = call("/api/player/rename", login.getToken(), RenameRequest.newBuilder().setDisplayName("新的玩家😀").build());
        assertThat(rename.getStatus()).isEqualTo(200);
        String key = DataKeys.key("player", login.getUserId(), "profile");
        var mongoStore = new MongoStore(mongo, MONGO_DB);
        await(() -> {
            String json = mongoStore.load(key);
            return json != null && json.contains("新的玩家😀")
                    && !redis.getSet(DirtyLedger.dirtySetOf(key), StringCodec.INSTANCE).contains(key)
                    && !redis.getSet(DirtyLedger.inflightSetOf(DataKeys.bucketOf(key)), StringCodec.INSTANCE).contains(key);
        });
        new RedisStore(redis).del(key);
        assertThat(profile(login.getToken()).getDisplayName()).isEqualTo("新的玩家😀");
        assertThat(new RedisStore(redis).get(key)).contains("新的玩家😀");
        var second = login("alice");
        assertThat(second.getUserId()).isEqualTo(login.getUserId());
        assertThat(call("/api/player/profile", login.getToken(), ProfileRequest.getDefaultInstance()).getStatus()).isEqualTo(401);
        assertThat(call("/api/auth/logout", second.getToken(), LogoutRequest.getDefaultInstance()).getStatus()).isEqualTo(200);
        assertThat(call("/api/player/profile", second.getToken(), ProfileRequest.getDefaultInstance()).getStatus()).isEqualTo(401);
        var expiring = login("alice");
        var identity = web.getBean(SessionStore.class).authenticate(expiring.getToken());
        var sessionKey = redis.getBucket("auth:session:" + identity.tokenDigest(), StringCodec.INSTANCE);
        sessionKey.expire(Duration.ofMillis(1));
        await(() -> !sessionKey.isExists());
        assertThat(call("/api/player/profile", expiring.getToken(), ProfileRequest.getDefaultInstance()).getStatus()).isEqualTo(401);
        try (var files = java.nio.file.Files.list(logDirectory)) {
            assertThat(files.anyMatch(p -> p.getFileName().toString().endsWith(".jsonl"))).isTrue();
        }
    }

    @Test
    void accountsAreIsolatedAndFailedDiskLogDoesNotChangeProfile() throws Exception {
        register("alice");
        register("bob");
        var a = login("alice");
        var b = login("bob");
        call("/api/player/rename", a.getToken(), RenameRequest.newBuilder().setDisplayName("Alice only").build());
        assertThat(profile(b.getToken()).getDisplayName()).isNotEqualTo("Alice only");
        web.getBean(FileBizLogger.class).close();
        var failed = call("/api/player/rename", a.getToken(), RenameRequest.newBuilder().setDisplayName("Should fail").build());
        assertThat(failed.getStatus()).isEqualTo(503);
        assertThat(profile(a.getToken()).getDisplayName()).isEqualTo("Alice only");
    }
}
