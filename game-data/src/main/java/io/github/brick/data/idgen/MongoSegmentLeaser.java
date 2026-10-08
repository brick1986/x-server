package io.github.brick.data.idgen;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;

/**
 * Mongo counters 账本（发号 spec §4.1）。
 *
 * <p>counters 是基础设施元数据，**不是实体数据**：不进 Redis、不走 CommitLua/dirty
 * 流水线、不受 flushdb 影响，{@code game-dbserver} 对它无感知。账本只增不减，因此
 * 发号器不存在恢复链——Redis 丢什么都与发号无关。这同时是否决 Redis INCR 方案的理由
 * （发号 spec §2：Redis 丢账本从 Mongo max 回种有 1~3s 落盘滞后窗口，重号即覆盖）。
 *
 * <p>独立成类而非塞进 {@code MongoStore}：MongoStore 只管 {@code {entity}:{field}}
 * 集合上的 {@code {_id, v}} 实体文档（collection 由 DataKeys 派生）；counters 的
 * 集合名固定、文档形态 {@code {_id, seq}}，职责不同。
 */
public final class MongoSegmentLeaser implements SegmentLeaser {

    /** 集合名固定（发号 spec §4.1）。 */
    public static final String COLLECTION = "counters";

    private final MongoCollection<Document> counters;

    public MongoSegmentLeaser(MongoClient client, String dbName) {
        this.counters = client.getDatabase(dbName).getCollection(COLLECTION, Document.class);
    }

    /**
     * 原子租段：{@code findOneAndUpdate($inc: {seq: count})} 返回新值（段末位）。
     * Mongo 单文档修改排队执行，并发租段物理上拿不到重叠段（发号 spec §2）；
     * counter 不存在时 upsert，新值 = count，段 = [1, count]。
     */
    @Override
    public long lease(String counterName, int count) {
        Document after = counters.findOneAndUpdate(
                new Document("_id", counterName),
                new Document("$inc", new Document("seq", (long) count)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return after.getLong("seq");
    }
}
