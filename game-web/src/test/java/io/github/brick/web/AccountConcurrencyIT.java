package io.github.brick.web;
import io.github.brick.web.support.WebIntegrationSupport;
import io.github.brick.web.auth.*;
import io.github.brick.web.api.GameException;
import io.github.brick.web.domain.account.MongoAccountRepository;
import io.github.brick.web.domain.player.PlayerService;
import io.github.brick.data.idgen.*;
import java.time.Clock;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountConcurrencyIT extends WebIntegrationSupport {
    @Test void simultaneousRegistrationAndLoginReserveOneRoleAndOneCurrentToken() throws Exception {
        startFlusher();
        var first=web.getBean(AuthService.class);
        var second=new AuthService(new MongoAccountRepository(mongo,MONGO_DB),web.getBean(PasswordHasher.class),
            new SegmentIdGenerator(1,100,new MongoSegmentLeaser(mongo,MONGO_DB),new MongoServerIdGuard(mongo,MONGO_DB)),
            web.getBean(PlayerService.class),web.getBean(SessionStore.class),Clock.systemUTC());
        var gate=new CyclicBarrier(2);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{gate.await();return registerOnce(first);});
            var b=pool.submit(()->{gate.await();return registerOnce(second);});
            assertThat(a.get(15,TimeUnit.SECONDS)^b.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(db().getCollection("accounts").countDocuments()).isEqualTo(1);
            var x=pool.submit(()->{gate.await();return first.login("alice",PASSWORD,"x");});
            var y=pool.submit(()->{gate.await();return second.login("alice",PASSWORD,"y");});
            var one=x.get(15,TimeUnit.SECONDS);var two=y.get(15,TimeUnit.SECONDS);
            assertThat(one.profile().uid()).isEqualTo(two.profile().uid());
            assertThat(valid(one.session().token())^valid(two.session().token())).isTrue();
            await(()->db().getCollection("player:profile").countDocuments()==1);
            var active=valid(one.session().token())?one.session():two.session();
            web.getBean(PlayerService.class).rename(active.identity(),"Retained","rename");
            var againA=pool.submit(()->{gate.await();return first.login("alice",PASSWORD,"again-a");});
            var againB=pool.submit(()->{gate.await();return second.login("alice",PASSWORD,"again-b");});
            assertThat(againA.get(15,TimeUnit.SECONDS).profile().displayName()).isEqualTo("Retained");
            assertThat(againB.get(15,TimeUnit.SECONDS).profile().displayName()).isEqualTo("Retained");
        }
    }
    private boolean registerOnce(AuthService service) {
        try{service.register("alice",PASSWORD);return true;}
        catch(GameException error){assertThat(error.status()).isEqualTo(409);return false;}
    }
    private boolean valid(String token){try{web.getBean(SessionStore.class).authenticate(token);return true;}catch(GameException e){return false;}}
}
