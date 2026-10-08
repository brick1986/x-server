package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSegmentLeaserIT extends LocalRedisMongo {

    private MongoSegmentLeaser leaser() {
        return new MongoSegmentLeaser(mongo, MONGO_DB);
    }

    @Test
    void firstLeaseStartsSegmentAtOne() {
        // 首租 upsert：新 seq = count，段 = [1, count]——流水从 1 起（发号 spec §3）
        assertThat(leaser().lease(IdScheme.counterName(1, "player"), 100)).isEqualTo(100L);
    }

    @Test
    void leasesAccumulateOnSameCounter() {
        MongoSegmentLeaser l = leaser();
        l.lease(IdScheme.counterName(1, "player"), 100);
        assertThat(l.lease(IdScheme.counterName(1, "player"), 7)).isEqualTo(107L);
    }

    @Test
    void countersAreIndependent() {
        MongoSegmentLeaser l = leaser();
        l.lease(IdScheme.counterName(1, "player"), 100);
        assertThat(l.lease(IdScheme.counterName(1, "guild"), 50)).isEqualTo(50L);
    }

    @Test
    void counterDocShapeFollowsSpec() {
        leaser().lease(IdScheme.counterName(1, "player"), 100);
        Document doc = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:player")).first();
        assertThat(doc).isNotNull();
        assertThat(doc.getLong("seq")).isEqualTo(100L);
    }

    @Test
    void concurrentLeasesNeverOverlap() throws Exception {
        // 两个实例各 100 次并发租段（count=10）：原子 $inc 下 200 个段末位
        // 必然恰好是 10,20,…,2000——排序后公差恒 10 即证明互不重叠也无跳变
        MongoSegmentLeaser a = leaser();
        MongoSegmentLeaser b = leaser();
        List<Long> ends = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (MongoSegmentLeaser l : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 100; i++) {
                        // 租段必须在监视器外做：lease 处于 synchronized 内时两 worker 互斥，
                        // 任意时刻最多一个 findOneAndUpdate 在飞——200 次租段实际串行，测不出并发不重叠
                        long end = l.lease("idgen:1:player", 10);
                        synchronized (ends) { ends.add(end); }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(ends).hasSize(200);
        ends.sort(Long::compare);
        for (int i = 0; i < ends.size(); i++) {
            assertThat(ends.get(i)).isEqualTo(10L * (i + 1));
        }
    }
}
