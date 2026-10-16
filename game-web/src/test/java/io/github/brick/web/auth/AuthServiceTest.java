package io.github.brick.web.auth;
import io.github.brick.data.idgen.IdGenerator;
import io.github.brick.web.domain.account.*;
import io.github.brick.web.domain.player.*;
import io.github.brick.web.api.GameException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthServiceTest {
    @Test void registrationUsesSeparateIdsAndDoesNotLeakCredentials() {
        var repo=mock(MongoAccountRepository.class); var ids=mock(IdGenerator.class);
        when(ids.next("account")).thenReturn(101L); when(ids.next("player")).thenReturn(202L);
        var svc=new AuthService(repo,new PasswordHasher(new SecureRandom()),ids,mock(PlayerService.class),mock(SessionStore.class),Clock.systemUTC());
        var result=svc.register(" Alice ","correct password 123");
        assertThat(result.accountId()).isEqualTo(101); assertThat(result.playerId()).isEqualTo(202);
        assertThat(result.username()).isEqualTo("alice"); assertThat(result.playerInitialized()).isFalse();
        assertThat(result.toString()).doesNotContain("correct password",result.passwordHash());
        verify(repo).insert(result);
    }
    @Test void invalidCredentialsAndFailedCreationNeverIssueToken() {
        var repo=mock(MongoAccountRepository.class); var passwords=spy(new PasswordHasher(new SecureRandom()));
        var players=mock(PlayerService.class); var sessions=mock(SessionStore.class);
        var svc=new AuthService(repo,passwords,mock(IdGenerator.class),players,sessions,Clock.systemUTC());
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        assertThatThrownBy(()->svc.login("alice","incorrect password","r")).isInstanceOfSatisfying(GameException.class,e->assertThat(e.status()).isEqualTo(401));
        verify(passwords).matches(eq("incorrect password"),anyString()); verifyNoInteractions(players,sessions);
        String encoded=passwords.hash("correct password 123");
        when(repo.findByUsername("alice")).thenReturn(Optional.of(new Account(1,"alice",encoded,11,false,100)));
        assertThatThrownBy(()->svc.login("alice","incorrect password","r")).isInstanceOf(GameException.class);
        when(players.ensureInitialized(1,"r")).thenThrow(GameException.integrity());
        assertThatThrownBy(()->svc.login("alice","correct password 123","r")).isInstanceOf(GameException.class);
        verifyNoInteractions(sessions);
    }
}
