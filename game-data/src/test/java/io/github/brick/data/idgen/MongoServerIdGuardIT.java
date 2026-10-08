package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoServerIdGuardIT extends LocalRedisMongo {

    private MongoServerIdGuard guard() {
        return new MongoServerIdGuard(mongo, MONGO_DB);
    }

    @Test
    void emptyLibraryPassesAndMarks() {
        guard().checkAndMark(1);
        Document boot = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:boot")).first();
        assertThat(boot).isNotNull();
        assertThat(boot.getString("bootAt")).isNotBlank();
    }

    @Test
    void rejectsForeignServerId() {
        guard().checkAndMark(1);
        // 同一库出现第二个服号 = 配置漂移（发号 spec §5.2），fail-fast
        assertThatThrownBy(() -> guard().checkAndMark(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idgen:1:boot")
                .hasMessageContaining("server-id=2");
    }

    @Test
    void sameServerIdIsIdempotent() {
        MongoServerIdGuard g = guard();
        g.checkAndMark(1);
        g.checkAndMark(1);   // 不抛——同服多进程并发首号各自 upsert 同一文档
        assertThat(db().getCollection("counters")
                .countDocuments(new Document("_id", java.util.regex.Pattern.compile("^idgen:\\d+:boot$"))))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameServerMarkIsSafe() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                fs.add(pool.submit(() -> { go.await(); guard().checkAndMark(1); return null; }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        // 8 个并发标记后恰好 1 个 boot 文档、无异常
        assertThat(db().getCollection("counters")
                .countDocuments(new Document("_id", java.util.regex.Pattern.compile("^idgen:\\d+:boot$"))))
                .isEqualTo(1);
    }
}
