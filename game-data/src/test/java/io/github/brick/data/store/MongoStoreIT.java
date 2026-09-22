package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MongoStoreIT extends LocalRedisMongo {

    private MongoStore store() {
        return new MongoStore(mongo, MONGO_DB);
    }

    @Test
    void loadMissingReturnsNull() {
        assertThat(store().load(DataKeys.key("player", 1, "profile"))).isNull();
    }

    @Test
    void upsertThenLoadRoundTrip() {
        MongoStore s = store();
        s.upsert(DataKeys.key("player", 1, "profile"), "{\"name\":\"alice\"}");
        assertThat(s.load(DataKeys.key("player", 1, "profile"))).isEqualTo("{\"name\":\"alice\"}");
    }

    @Test
    void upsertIsIdempotentReplace() {
        MongoStore s = store();
        s.upsert(DataKeys.key("player", 1, "profile"), "{\"v\":1}");
        s.upsert(DataKeys.key("player", 1, "profile"), "{\"v\":2}");
        assertThat(s.load(DataKeys.key("player", 1, "profile"))).isEqualTo("{\"v\":2}");
    }

    @Test
    void loadUsesCollectionEntityColonFieldAndIdDocId() {
        MongoStore s = store();
        s.upsert(DataKeys.key("player", 123, "profile"), "{}");
        Document doc = db().getCollection("player:profile").find(new Document("_id", 123L)).first();
        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isEqualTo(123L);
    }

    @Test
    void bulkUpsertWritesAllAndLoadsBack() {
        MongoStore s = store();
        assertThat(s.bulkUpsert(Map.of(
                DataKeys.key("player", 1, "profile"), "{\"p\":1}",
                DataKeys.key("player", 2, "bag"), "{\"b\":2}",
                DataKeys.key("guild", 7, "fund"), "{\"f\":7}"))).isEmpty();
        assertThat(s.load(DataKeys.key("player", 1, "profile"))).isEqualTo("{\"p\":1}");
        assertThat(s.load(DataKeys.key("player", 2, "bag"))).isEqualTo("{\"b\":2}");
        assertThat(s.load(DataKeys.key("guild", 7, "fund"))).isEqualTo("{\"f\":7}");
    }

    @Test
    void bulkUpsertReturnsEmptySetWhenAllSucceed() {
        assertThat(store().bulkUpsert(Map.of(DataKeys.key("player", 1, "profile"), "{\"p\":1}"))).isEmpty();
    }

    @Test
    void bulkUpsertEmptyInputReturnsEmptySet() {
        assertThat(store().bulkUpsert(Map.of())).isEmpty();
    }

    @Test
    void bulkUpsertReturnsOnlyFailedKeyAndStillWritesTheRest() {
        // 真实世界的失败触发是文档超 16MB（架构 §4.1 自己留了这个伏笔），但那要造 17MB
        // 字符串、慢且吃内存。此处用唯一索引冲突制造「同批中恰好一条写失败」——确定性更强、
        // 更快，且走的是同一条 MongoBulkWriteException + getWriteErrors() 反查路径。
        db().getCollection("player:profile")
                .createIndex(new Document("v", 1), new IndexOptions().unique(true));
        MongoStore s = store();
        s.upsert(DataKeys.key("player", 1, "profile"), "{\"dup\":1}");

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(DataKeys.key("player", 2, "profile"), "{\"dup\":1}");   // v 与 player:1 重复 → 唯一索引冲突
        batch.put(DataKeys.key("player", 3, "profile"), "{\"ok\":3}");

        assertThat(s.bulkUpsert(batch)).containsExactly(DataKeys.key("player", 2, "profile"));
        // unordered 的关键收益：失败那条没有拖累同批其余（ordered 下 player:3 根本不会执行）
        assertThat(s.load(DataKeys.key("player", 3, "profile"))).isEqualTo("{\"ok\":3}");
        assertThat(s.load(DataKeys.key("player", 2, "profile"))).isNull();
    }

    @Test
    void bulkUpsertMapsErrorIndexBackToKeyWithinItsOwnCollection() {
        // BulkWriteError.getIndex() 是「该次 bulkWrite 调用内 models 列表的下标」，
        // 不是整批 entries 的下标。多 collection 分组时若拿整批下标反查会错位到别的 key，
        // 把一个落盘成功的 key 误判为失败、并把真正失败的那个漏掉。
        db().getCollection("guild:fund")
                .createIndex(new Document("v", 1), new IndexOptions().unique(true));
        MongoStore s = store();
        s.upsert(DataKeys.key("guild", 1, "fund"), "{\"dup\":1}");

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(DataKeys.key("player", 1, "profile"), "{\"p\":1}");     // player:profile 组
        batch.put(DataKeys.key("player", 2, "profile"), "{\"p\":2}");     // player:profile 组
        batch.put(DataKeys.key("guild", 2, "fund"), "{\"dup\":1}");       // guild:fund 组内下标 0，冲突

        assertThat(s.bulkUpsert(batch)).containsExactly(DataKeys.key("guild", 2, "fund"));
        assertThat(s.load(DataKeys.key("player", 1, "profile"))).isEqualTo("{\"p\":1}");
        assertThat(s.load(DataKeys.key("player", 2, "profile"))).isEqualTo("{\"p\":2}");
    }
}
