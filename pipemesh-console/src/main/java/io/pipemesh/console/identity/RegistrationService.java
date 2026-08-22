package io.pipemesh.console.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Opening an account: one organization, one person, one link to prove the
 * address.
 */
@Service
public class RegistrationService {

    /** Long enough to survive a mail queue, short enough that a leaked link ages out. */
    private static final Duration LINK_VALIDITY = Duration.ofHours(24);

    private final IdentityRepository accounts;
    private final PasswordEncoder passwords;
    private final VerificationLinkSender links;
    private final Clock clock;
    private final String defaultPlan;

    /**
     * @param defaultPlan which plan a new account lands on. Configuration rather
     *                    than a constant, because the answer differs by
     *                    deployment: {@code demo} where accounts are handed out
     *                    to strangers, {@code unlimited} where somebody bought a
     *                    licence and being stopped at fifty executions would be
     *                    absurd. This class used to carry the comment without the
     *                    behaviour.
     */
    public RegistrationService(
            IdentityRepository accounts, PasswordEncoder passwords,
            VerificationLinkSender links, Clock clock,
            @Value("${console.defaultPlan:demo}") String defaultPlan) {

        this.accounts = accounts;
        this.passwords = passwords;
        this.links = links;
        this.clock = clock;
        this.defaultPlan = defaultPlan;
    }

    /** Creates the organization and its first user, on the configured plan. */
    @Transactional
    public Organization register(String organizationName, String email, String password) {
        String address = normalise(email);
        if (accounts.findUserByEmail(address).isPresent()) {
            throw new EmailAlreadyRegisteredException(address);
        }

        Organization organization = new Organization(
                UUID.randomUUID().toString(), organizationName, defaultPlan, clock.instant());
        accounts.insertOrganization(organization);

        ConsoleUser user = new ConsoleUser(
                UUID.randomUUID().toString(), organization.id(), address,
                passwords.encode(password), null);
        accounts.insertUser(user);

        sendVerification(user);
        return organization;
    }

    /**
     * Claims a verification link.
     *
     * <p>Single use and time limited, both enforced by the same statement that
     * marks it used — checking first and writing second would let two clicks
     * arriving together both succeed.
     */
    @Transactional
    public void verify(String token) {
        Instant now = clock.instant();
        String userId = accounts.claimVerification(Tokens.hash(token), now)
                .orElseThrow(() -> new InvalidVerificationException(
                        "this link has already been used, or has expired"));

        accounts.markVerified(userId, now);
    }

    private void sendVerification(ConsoleUser user) {
        String token = Tokens.generate();
        accounts.insertVerification(
                Tokens.hash(token), user.id(), clock.instant().plus(LINK_VALIDITY));
        links.send(user.email(), token);
    }

    /** Addresses are compared as one case, or two people could own the same one. */
    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
