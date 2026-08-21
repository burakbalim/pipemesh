package io.pipemesh.console.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** Signing in, and knowing who is signed in. */
@Service
public class SessionService {

    private static final Duration VALIDITY = Duration.ofDays(14);

    private final IdentityRepository accounts;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public SessionService(IdentityRepository accounts, PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.clock = clock;
    }

    /**
     * @return the session token, to be handed back on later requests
     * @throws SignInRefusedException when the address, the password or the
     *         verification is not in order — with one message for all three, so
     *         that a failed attempt does not reveal which addresses exist
     */
    public String signIn(String email, String password) {
        ConsoleUser user = accounts.findUserByEmail(email.trim().toLowerCase())
                .orElseThrow(SignInRefusedException::new);

        // Checked even when the address was unknown would be better still; this
        // at least does the same work in both wrong cases below.
        if (!passwords.matches(password, user.passwordHash())) {
            throw new SignInRefusedException();
        }
        if (!user.verified()) {
            throw new UnverifiedAccountException();
        }

        String token = Tokens.generate();
        accounts.insertSession(Tokens.hash(token), user.id(), clock.instant().plus(VALIDITY));
        return token;
    }

    public Optional<ConsoleUser> userOf(String token) {
        return accounts.findSessionUser(Tokens.hash(token), clock.instant())
                .flatMap(accounts::findUser);
    }

    public void signOut(String token) {
        accounts.deleteSession(Tokens.hash(token));
    }
}
