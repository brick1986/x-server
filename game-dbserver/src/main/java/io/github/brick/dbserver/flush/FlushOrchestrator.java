package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮落盘的完整编排（落盘 spec §2~§4）。无状态，谁都能调——定时轮次调它，停机刷盘也调它。
 *
 * <p>流程：原子排空 dirty → 按 {@code chunkSize} 分片 → 每片 {@code MGET} 取值（还 Redis 连接）
 * → {@code bulkUpsert} 写 Mongo → 失败 key 回写 dirty → 整片移出 in-flight。
 *
 * <p><b>禁嵌套跨池</b>（并发修订 §3.2）：{@code mget} 返回时 Redis 连接已归还，之后才触达 Mongo，
 * 一个虚拟线程任一时刻只持有一个池的连接。
 *
 * <p><b>分片是为了让内存与单次往返量只与片大小有关、与积压总量无关</b>：冷启动首轮或积压上万时，
 * 逐个 {@code GET} 是上万次同步往返，而一次攒上万个 {@code Document} 才是真问题（落盘 spec §3）。
 *
 * <p><b>中断与崩溃路径零操作</b>：剩余 key 留在 in-flight，由下一轮 {@code drainToInflight}
 * 的恢复步骤合回 dirty（落盘 spec §2.3）。故本类没有任何「回滚」或「清理」分支。
 */
public class FlushOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlushOrchestrator.class);

    /** {@link #flushOnce()} 的第三态：本轮没干成事（非「已刷空」）。见落盘 spec §3.1 返回值契约。 */
    public static final int INTERRUPTED = -1;

    private final DirtyLedger dirty;
    private final RedisStore redis;
    private final MongoStore mongo;
    private final int chunkSize;

    public FlushOrchestrator(DirtyLedger dirty, RedisStore redis, MongoStore mongo, int chunkSize) {
        this.dirty = dirty;
        this.redis = redis;
        this.mongo = mongo;
        this.chunkSize = chunkSize;
    }

    /**
     * 跑一轮落盘。
     *
     * @return 本轮排空的 key 数（含其中落盘失败、已回写 dirty 的）；{@code 0} 表示 drain 没找到
     *         任何 key——**只有这一种情况才代表「真刷空了」**，停机循环据此收敛（落盘 spec §3.1）。
     *         返回排空数而非成功数是刻意的：若一批全部失败却返回 0，停机循环会把「dirty 里还有货」
     *         误判成刷完而提前退出。
     * @throws com.mongodb.MongoException Mongo 连接级故障，本轮中断；剩余 key 留在 in-flight
     */
    public int flushOnce() {
        Set<String> keys = dirty.drainToInflight();
        if (keys.isEmpty()) {
            return 0;
        }
        for (List<String> chunk : chunks(keys, chunkSize)) {
            flushChunk(chunk);
        }
        return keys.size();
    }

    private void flushChunk(List<String> chunk) {
        Map<String, String> values = redis.mget(chunk);      // 一次往返；返回即还 Redis 连接

        // mget 不把不存在的 key 映射到 null，而是不放进 Map，故差值即 nil 数量。
        // 这些 key 跳过 upsert 且**不回 dirty**——回了就是永久重试的死循环。
        int missing = chunk.size() - values.size();
        if (missing > 0) {
            log.warn("本片 {} 个 key 在 Redis 已不存在（标记残留），跳过落盘且不回写 dirty", missing);
        }

        Set<String> failed = mongo.bulkUpsert(values);
        if (!failed.isEmpty()) {
            log.warn("落盘失败 {} 个 key，回写 dirty 等下轮重试: {}", failed.size(), failed);
            dirty.markAll(failed);      // 顺序要求：必须先 mark 再 ack（落盘 spec §2.4）
        }
        dirty.ackInflight(chunk);       // 两步之间崩溃只会导致整片被下轮重放，upsert 幂等
    }

    /** 定长切片。包级可见供单测——切分边界（空集/整数倍/余数）值得独立于 Redis 验证。 */
    static List<List<String>> chunks(Collection<String> keys, int size) {
        List<String> all = new ArrayList<>(keys);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return out;
    }
}
