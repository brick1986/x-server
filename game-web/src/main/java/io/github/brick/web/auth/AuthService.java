package io.github.brick.web.auth;

import io.github.brick.data.idgen.IdGenerator;
import io.github.brick.web.domain.account.*;
import io.github.brick.web.domain.player.*;
import io.github.brick.web.api.GameException;

import java.time.Clock;

public class AuthService {
    // Valid-sized dummy record: unknown accounts still perform the full KDF; never authorizes a user.
    private static final String DUMMY = "pbkdf2-sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final MongoAccountRepository accounts;
    private final PasswordHasher passwords;
    private final IdGenerator ids;
    private final PlayerService players;
    private final SessionStore sessions;
    private final Clock clock;

    public AuthService(MongoAccountRepository accounts, PasswordHasher passwords, IdGenerator ids, PlayerService players, SessionStore sessions, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.ids = ids;
        this.players = players;
        this.sessions = sessions;
        this.clock = clock;
    }

    public record LoginResult(SessionStore.IssuedSession session, PlayerProfile profile) {
    }

    public Account register(String username, String password) {
        String name = CredentialRules.normalizeUsername(username);
        CredentialRules.validatePassword(password);
        String encoded = passwords.hash(password);
        var account = new Account(ids.next("account"), name, encoded, ids.next("player"), false, clock.millis());
        accounts.insert(account);
        return account;
    }

    public LoginResult login(String username, String password, String requestId) {
        String name = CredentialRules.normalizeUsername(username);
        CredentialRules.validatePassword(password);
        var account = accounts.findByUsername(name);
        boolean valid = passwords.matches(password, account.map(Account::passwordHash).orElse(DUMMY));
        if (!valid || account.isEmpty()) throw GameException.unauthorized();
        PlayerProfile profile = players.ensureInitialized(account.get().accountId(), requestId);
        return new LoginResult(sessions.issue(profile.accountId(), profile.uid()), profile);
    }
}
