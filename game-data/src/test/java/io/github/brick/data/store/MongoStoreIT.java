package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MongoStoreIT extends LocalRedisMongo {

    private MongoStore store() {
        return new MongoStore(mongo, MONGO_DB);
    }

    @Test
    void loadMissingReturnsNull() {
        assertThat(store().load("player:1:profile")).isNull();
    }

    @Test
    void upsertThenLoadRoundTrip() {
        MongoStore s = store();
        s.upsert("player:1:profile", "{\"name\":\"alice\"}");
        assertThat(s.load("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
    }

    @Test
    void upsertIsIdempotentReplace() {
        MongoStore s = store();
        s.upsert("player:1:profile", "{\"v\":1}");
        s.upsert("player:1:profile", "{\"v\":2}");
        assertThat(s.load("player:1:profile")).isEqualTo("{\"v\":2}");
    }

    @Test
    void loadUsesCollectionEntityColonFieldAndIdDocId() {
        MongoStore s = store();
        s.upsert("player:123:profile", "{}");
        Document doc = db().getCollection("player:profile").find(new Document("_id", 123L)).first();
        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isEqualTo(123L);
    }

    @Test
    void bulkUpsertWritesAllAndLoadsBack() {
        MongoStore s = store();
        s.bulkUpsert(Map.of(
                "player:1:profile", "{\"p\":1}",
                "player:2:bag", "{\"b\":2}",
                "guild:7:fund", "{\"f\":7}"));
        assertThat(s.load("player:1:profile")).isEqualTo("{\"p\":1}");
        assertThat(s.load("player:2:bag")).isEqualTo("{\"b\":2}");
        assertThat(s.load("guild:7:fund")).isEqualTo("{\"f\":7}");
    }
}
