package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorIT extends LocalRedisMongo {

    private SegmentIdGenerator gen(int serverId, int segmentSize) {
        return new SegmentIdGenerator(serverId, segmentSize,
                new MongoSegmentLeaser(mongo, MONGO_DB),
                new MongoServerIdGuard(mongo, MONGO_DB));
    }

    @Test
    void twoGeneratorsConcurrentlyNeverDuplicate() throws Exception {
        // 发号 spec §8.4：两实例（同库同 counter）并发各发 1000 个，段长 100 → 各租 10 段
        SegmentIdGenerator a = gen(1, 100);
        SegmentIdGenerator b = gen(1, 100);
        Set<Long> all = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (SegmentIdGenerator g : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 1000; i++) all.add(g.next("player"));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        }
        assertThat(all).hasSize(2000);   // 零重复 = 账本租段原子性的端到端证明
    }

    @Test
    void rebuiltInstanceContinuesWithoutReissue() {
        // 发号 spec §8.6：丢段只产生空洞（跳号），绝不重号
        SegmentIdGenerator a = gen(1, 100);
        a.next("guild");
        a.next("guild");                 // 本地段 [1,100] 只发了 2 个就"崩溃"丢弃
        SegmentIdGenerator b = gen(1, 100);   // 重建实例：租新段
        assertThat(b.next("guild")).isEqualTo(IdScheme.compose(1, 101));
    }

    @Test
    void leaseBypassesLocalSegment() {
        // 发号 spec §8.5：lease 直租账本，与本地段无关
        SegmentIdGenerator g = gen(1, 100);
        g.next("mail");                   // 本地段 [1,100]
        long start = g.lease("mail", 100000);
        assertThat(start).isEqualTo(IdScheme.compose(1, 101));   // 新段 [101,100100]
        Document seqDoc = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:mail")).first();
        assertThat(seqDoc.getLong("seq")).isEqualTo(100100L);
    }

    @Test
    void foreignServerIdFailsFastOnFirstIssue() {
        // 自检挂进首次发号路径（懒）：配错服号 → 首个 next 即抛，静默发号不存在
        SegmentIdGenerator one = gen(1, 100);
        one.next("player");
        SegmentIdGenerator two = gen(2, 100);   // 同库不同服号
        assertThatThrownBy(() -> two.next("player"))
                .isInstanceOf(IllegalStateException.class);
    }
}
