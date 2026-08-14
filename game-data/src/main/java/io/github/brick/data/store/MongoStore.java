package io.github.brick.data.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mongo JSON 文档封装：{@code load}/{@code upsert}/{@code bulkUpsert}。文档形态 {@code {_id, v}}，
 * 落盘零转换——存的就是 Redis 那份 JSON 字符串，不反序列化成 Java 对象（架构 spec §4.1、§4.3）。
 * collection = {@code {entity}:{field}}、_id = {id}（Mongo 映射决策 A，由 DataKeys 派生）。
 */
public final class MongoStore {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

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

    public void bulkUpsert(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        // 按 collection 分组各跑一次 bulkWrite（WriteModel 必须同集合）
        Map<String, List<WriteModel<Document>>> byCol = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            byCol.computeIfAbsent(DataKeys.collectionOf(key), k -> new ArrayList<>())
                    .add(new ReplaceOneModel<>(
                            byId(key),
                            new Document("_id", DataKeys.docIdOf(key)).append("v", e.getValue()),
                            UPSERT));
        }
        byCol.forEach((col, models) -> db().getCollection(col, Document.class).bulkWrite(models));
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
