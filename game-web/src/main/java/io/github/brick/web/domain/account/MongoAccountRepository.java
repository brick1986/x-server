package io.github.brick.web.domain.account;
import com.mongodb.MongoWriteException;
import com.mongodb.client.*;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import java.util.Optional;
import io.github.brick.web.api.GameException;

public class MongoAccountRepository {
    private final MongoCollection<Document> accounts;
    private volatile boolean indexed;
    public MongoAccountRepository(MongoClient client, String dbName) { accounts = client.getDatabase(dbName).getCollection("accounts"); }
    private void ensureIndex() {
        if (indexed) return;
        synchronized (this) {
            if (!indexed) {
                accounts.createIndex(new Document("username", 1), new IndexOptions().unique(true).name("username_unique"));
                indexed = true;
            }
        }
    }
    public void insert(Account a) {
        ensureIndex();
        try {
            accounts.insertOne(new Document("_id",a.accountId()).append("username",a.username())
                .append("passwordHash",a.passwordHash()).append("playerId",a.playerId())
                .append("playerInitialized",a.playerInitialized()).append("createdAt",a.createdAt()));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) throw new GameException(409,"ACCOUNT_EXISTS","账号已存在");
            throw e;
        }
    }
    public Optional<Account> findByUsername(String name) { return find(new Document("username",name)); }
    public Optional<Account> findById(long id) { return find(new Document("_id",id)); }
    private Optional<Account> find(Document query) {
        Document d = accounts.find(query).first();
        return d == null ? Optional.empty() : Optional.of(new Account(d.getLong("_id"), d.getString("username"),
            d.getString("passwordHash"), d.getLong("playerId"), d.getBoolean("playerInitialized",false), d.getLong("createdAt")));
    }
    public void markPlayerInitialized(long accountId, long playerId) {
        var result = accounts.updateOne(new Document("_id", accountId).append("playerId",playerId),
            new Document("$set",new Document("playerInitialized",true)));
        if (result.getMatchedCount() != 1) throw GameException.integrity();
    }
}
