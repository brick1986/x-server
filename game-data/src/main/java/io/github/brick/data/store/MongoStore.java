package io.github.brick.data.store;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mongo JSON 文档封装：{@code load}/{@code upsert}/{@code bulkUpsert}。文档形态 {@code {_id, v}}，
 * 落盘零转换——存的就是 Redis 那份 JSON 字符串，不反序列化成 Java 对象（架构 spec §4.1、§4.3）。
 * collection = {@code {entity}:{field}}、_id = {id}（Mongo 映射决策 A，由 DataKeys 派生）。
 */
public final class MongoStore {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    /**
     * unordered：落盘的每个 key 相互独立，一条失败不该让同批其余一条都不执行；
     * 且 unordered 会尝试全部 model，错误列表完整、每条带 index，可精确反查是哪个 key
     * （落盘 spec §4）。
     */
    private static final BulkWriteOptions UNORDERED = new BulkWriteOptions().ordered(false);

    private final MongoClient client;
    private final String dbName;

    public MongoStore(MongoClient client, String dbName) {
        this.client = client;
        this.dbName = dbName;
    }

    public String load(String key) {
        Document doc = collection(key).find(byId(key)).first();
        return doc == null ? null : doc.getString("v");
    }

    public void upsert(String key, String json) {
        collection(key).replaceOne(
                byId(key),
                new Document("_id", DataKeys.docIdOf(key)).append("v", json),
                UPSERT);
    }

    /**
     * 批量整值 upsert。按 collection 分组各跑一次 {@code bulkWrite}（{@code WriteModel} 必须同集合）。
     *
     * @return 写失败的 key；空集表示全部成功。失败者由调用方回写 dirty 等下轮重试（落盘 spec §4）
     * @throws com.mongodb.MongoException 连接级故障（Mongo 不可用）**原样抛出**——那是整片失败，
     *         不是个别 key 的问题，处置策略归调用方
     */
    public Set<String> bulkUpsert(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return Set.of();
        }
        // modelsByCol 与 keysByCol 在同一个循环里同序构建：BulkWriteError.getIndex() 是
        // 「该次 bulkWrite 调用内 models 列表的下标」，反查 key 只能靠这份同序列表——
        // 用整批 entries 的下标会错位到别的 collection 的 key。
        Map<String, List<WriteModel<Document>>> modelsByCol = new LinkedHashMap<>();
        Map<String, List<String>> keysByCol = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String col = DataKeys.collectionOf(key);
            modelsByCol.computeIfAbsent(col, k -> new ArrayList<>())
                    .add(new ReplaceOneModel<>(
                            byId(key),
                            new Document("_id", DataKeys.docIdOf(key)).append("v", e.getValue()),
                            UPSERT));
            keysByCol.computeIfAbsent(col, k -> new ArrayList<>()).add(key);
        }

        Set<String> failed = new LinkedHashSet<>();
        modelsByCol.forEach((col, models) -> {
            try {
                db().getCollection(col, Document.class).bulkWrite(models, UNORDERED);
            } catch (MongoBulkWriteException e) {
                List<String> keys = keysByCol.get(col);
                for (BulkWriteError err : e.getWriteErrors()) {
                    failed.add(keys.get(err.getIndex()));
                }
            }
        });
        return failed;
    }

    private MongoCollection<Document> collection(String key) {
        return db().getCollection(DataKeys.collectionOf(key), Document.class);
    }

    private Document byId(String key) {
        return new Document("_id", DataKeys.docIdOf(key));
    }

    private MongoDatabase db() {
        return client.getDatabase(dbName);
    }
}
