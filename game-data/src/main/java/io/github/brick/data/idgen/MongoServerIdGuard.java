package io.github.brick.data.idgen;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 服号自检（发号 spec §5.2）：本库 counters 已有**其他**服号的 boot 标记 = 同一库被
 * 两个服号交替使用，uid 位型前提被破坏，fail-fast（启动语义：首次发号必然在部署最早期）。
 *
 * <p>自检时机在**首次发号时**而非启动装配期——对齐懒连接哲学（primitives §3.4，
 * Redisson {@code setLazyInitialization(true)} 的同款取舍）：装配测试
 * （ApplicationContextRunner，不连 live Mongo）与不发号的模块不被迫触达 Mongo。
 *
 * <p>两个**独立**的服（各自独立的 Mongo）被运维配了同一个号，这里检测不到——
 * 不同库互不可见，这层靠运维纪律，代码防不住（发号 spec §5.2 诚实记录）。
 */
public final class MongoServerIdGuard implements ServerIdGuard {

    /** 用 \z 而非 $：该 Pattern 发给 Mongo 按 PCRE 执行，PCRE 的 $ 还匹配尾部换行前
     *  ——next("boot\n") 创建的 counter 会被误判为 boot 标记；\z 是纯串尾。 */
    private static final Pattern BOOT = Pattern.compile("^idgen:\\d+:boot\\z");

    private final MongoCollection<Document> counters;

    public MongoServerIdGuard(MongoClient client, String dbName) {
        this.counters = client.getDatabase(dbName).getCollection("counters", Document.class);
    }

    /** 幂等：同服号重复调用只 upsert 同一文档（bootAt 用 $setOnInsert，首次时间不被覆盖）。 */
    @Override
    public void checkAndMark(int serverId) {
        String mine = IdScheme.bootName(serverId);
        for (Document d : counters.find(new Document("_id", BOOT))) {
            if (!mine.equals(d.getString("_id"))) {
                throw new IllegalStateException("""
                        本 Mongo 库已被其他服号标记: %s，本进程 server-id=%d。
                        同一库被两个服号交替使用会破坏 uid 位型（发号 spec §3/§5.2），
                        请核对 game.data.idgen.server-id 配置。"""
                        .formatted(d.getString("_id"), serverId));
            }
        }
        counters.updateOne(
                new Document("_id", mine),
                new Document("$setOnInsert", new Document("bootAt", Instant.now().toString())),
                new UpdateOptions().upsert(true));
    }
}
