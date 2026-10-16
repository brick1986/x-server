package io.github.brick.web.support;

import com.google.protobuf.MessageLite;
import com.mongodb.client.MongoClient;
import io.github.brick.contract.game.*;
import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.config.DataProperties;
import io.github.brick.data.idgen.*;
import io.github.brick.data.lock.*;
import io.github.brick.data.overlay.*;
import io.github.brick.data.store.*;
import io.github.brick.dbserver.config.DbServerConfiguration;
import io.github.brick.web.api.*;
import io.github.brick.web.config.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RedissonClient;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Production HTTP filters/controllers and scheduled flusher, using isolated real databases. */
public abstract class WebIntegrationSupport extends LocalRedisMongo {
    protected static final String PASSWORD = "test-password-12345";
    @TempDir protected Path logDirectory;
    protected AnnotationConfigWebApplicationContext web;
    private AnnotationConfigApplicationContext flusher;
    private MockMvc mvc;

    @BeforeEach void startWeb() {
        web = new AnnotationConfigWebApplicationContext();
        web.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(web,
            "game.web.biz-log-directory=" + logDirectory.toString().replace('\\', '/'),
            "game.web.auth-ip-limit=10000", "game.web.auth-account-limit=1000");
        web.register(WebTestConfiguration.class);
        web.refresh();
        assertThat(Path.of(web.getBean(WebProperties.class).getBizLogDirectory())).isEqualTo(logDirectory);
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(
            web.getBean("requestBoundary", FilterRegistrationBean.class).getFilter(),
            web.getBean("authentication", FilterRegistrationBean.class).getFilter()).build();
    }

    protected void startFlusher() {
        flusher = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(flusher,
            "game.dbserver.flush-interval-millis=500", "game.dbserver.shutdown-timeout-seconds=2");
        flusher.registerBean(RedissonClient.class, LocalRedisMongoAccess::redisClient,
            definition -> definition.setDestroyMethodName("shutdown"));
        flusher.registerBean(MongoClient.class, LocalRedisMongoAccess::mongoClient,
            definition -> definition.setDestroyMethodName("close"));
        flusher.registerBean(RedisStore.class, () -> new RedisStore(flusher.getBean(RedissonClient.class)));
        flusher.registerBean(MongoStore.class, () -> new MongoStore(flusher.getBean(MongoClient.class), MONGO_DB));
        flusher.registerBean(DirtyLedger.class, () -> new DirtyLedger(flusher.getBean(RedissonClient.class)));
        flusher.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        flusher.register(DbServerConfiguration.class);
        flusher.refresh();
    }

    @AfterEach void stopContexts() {
        try { if (flusher != null) flusher.close(); }
        finally { if (web != null) web.close(); }
    }

    protected MockHttpServletResponse call(String path, String token, MessageLite body) throws Exception {
        var request = post(path).contentType("application/x-protobuf").content(body.toByteArray());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }
    protected void register(String username) throws Exception {
        assertThat(call("/api/auth/register", null,
            RegisterRequest.newBuilder().setUsername(username).setPassword(PASSWORD).build()).getStatus()).isEqualTo(200);
    }
    protected LoginResponse login(String username) throws Exception {
        var response = call("/api/auth/login", null,
            LoginRequest.newBuilder().setUsername(username).setPassword(PASSWORD).build());
        assertThat(response.getStatus()).isEqualTo(200);
        return LoginResponse.parseFrom(response.getContentAsByteArray());
    }
    protected PlayerProfileMessage profile(String token) throws Exception {
        var response = call("/api/player/profile", token, ProfileRequest.getDefaultInstance());
        assertThat(response.getStatus()).isEqualTo(200);
        return ProfileResponse.parseFrom(response.getContentAsByteArray()).getProfile();
    }
    protected void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Scheduled persistence did not finish within 15 seconds");
            Thread.sleep(50);
        }
    }

    private static class LocalRedisMongoAccess {
        static RedissonClient redisClient() { return newClient(); }
        static MongoClient mongoClient() { return newMongoClient(); }
    }

    @TestConfiguration
    @EnableWebMvc
    @Import({WebConfiguration.class, LockPriorityConfig.class, AuthController.class,
        PlayerController.class, ApiExceptionHandler.class})
    static class WebTestConfiguration {
        @Bean(destroyMethod="") RedissonClient redisClient() { return redis; }
        @Bean(destroyMethod="") MongoClient mongoClient() { return mongo; }
        @Bean DataProperties dataProperties() { var p = new DataProperties(); p.setMongoDb(MONGO_DB); return p; }
        @Bean JsonCodec jsonCodec() { return new JsonCodec(); }
        @Bean LockScope locks(EntityPriorities priorities) {
            return new RedissonLockScope(redis, new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec(), 2000, 10, priorities.byEntity());
        }
        @Bean IdGenerator ids() {
            return new SegmentIdGenerator(1, 100, new MongoSegmentLeaser(mongo, MONGO_DB),
                new MongoServerIdGuard(mongo, MONGO_DB));
        }
    }
}
