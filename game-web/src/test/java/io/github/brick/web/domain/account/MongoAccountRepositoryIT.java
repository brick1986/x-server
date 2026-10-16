package io.github.brick.web.domain.account;
import io.github.brick.data.LocalRedisMongo;
import io.github.brick.web.api.GameException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class MongoAccountRepositoryIT extends LocalRedisMongo {
    @Test void uniqueNameAcrossConcurrentRepositoriesAndStableRole() throws Exception {
        var first = new MongoAccountRepository(mongo, MONGO_DB);
        var second = new MongoAccountRepository(mongo, MONGO_DB);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> a = () -> { gate.await(); try { first.insert(new Account(1,"alice","redacted",11,false,100)); return true; } catch (GameException e) { assertThat(e.status()).isEqualTo(409); return false; } };
            Callable<Boolean> b = () -> { gate.await(); try { second.insert(new Account(2,"alice","redacted",12,false,100)); return true; } catch (GameException e) { assertThat(e.status()).isEqualTo(409); return false; } };
            var x = pool.submit(a); var y = pool.submit(b);
            assertThat(x.get(10,TimeUnit.SECONDS) ^ y.get(10,TimeUnit.SECONDS)).isTrue();
        }
        var account = first.findByUsername("alice").orElseThrow();
        first.markPlayerInitialized(account.accountId(), account.playerId());
        assertThat(first.findById(account.accountId()).orElseThrow().playerInitialized()).isTrue();
        assertThatThrownBy(() -> first.markPlayerInitialized(account.accountId(),999)).isInstanceOf(GameException.class);
        assertThat(db().getCollection("accounts").countDocuments()).isEqualTo(1);
    }
}
