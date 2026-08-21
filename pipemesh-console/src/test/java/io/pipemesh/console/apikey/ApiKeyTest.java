package io.pipemesh.console.apikey;

import io.grpc.Metadata;
import io.pipemesh.console.ConsoleTest;
import io.pipemesh.console.identity.IdentityRepository;
import io.pipemesh.console.identity.Organization;
import io.pipemesh.console.runtime.ConsolePrincipalResolver;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.OrganizationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key is what turns an anonymous caller into an organization — the answer
 * {@code PrincipalResolver} was written to wait for (§23, §22.2).
 */
class ApiKeyTest extends ConsoleTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    private ApiKeyService keys;

    @Autowired
    private ConsolePrincipalResolver resolver;

    @Autowired
    private IdentityRepository accounts;

    @Autowired
    private Clock clock;

    private String organization() {
        Organization organization = new Organization(
                UUID.randomUUID().toString(), "Acme", "demo", clock.instant());
        accounts.insertOrganization(organization);
        return organization.id();
    }

    private Metadata presenting(String key) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + key);
        return metadata;
    }

    @Test
    void aKeyIdentifiesItsOrganization() {
        String organizationId = organization();
        IssuedApiKey issued = keys.issue(organizationId, "laptop");

        Principal caller = resolver.resolve(presenting(issued.secret()));

        assertEquals(OrganizationId.of(organizationId), caller.organizationIfKnown().orElseThrow());
        assertFalse(caller.unrestricted(), "a key is not a licence to do everything");
    }

    @Test
    void theCallerCarriesThePlansPermissions() {
        IssuedApiKey issued = keys.issue(organization(), "laptop");

        Principal caller = resolver.resolve(presenting(issued.secret()));

        assertTrue(caller.holds("stream:watch"), "the demo plan includes live watching");
    }

    @Test
    void changingThePlanChangesWhatExistingKeysMayDo() {
        String organizationId = organization();
        IssuedApiKey issued = keys.issue(organizationId, "laptop");

        jdbc.update("UPDATE console_plan SET permissions = '{}' WHERE id = 'demo'");
        try {
            assertFalse(resolver.resolve(presenting(issued.secret())).holds("stream:watch"),
                    "permissions come from the plan, so nothing has to be reissued");
        } finally {
            jdbc.update("UPDATE console_plan SET permissions = ARRAY['stream:watch'] WHERE id = 'demo'");
        }
    }

    @Test
    void theSecretIsNeverStored() {
        IssuedApiKey issued = keys.issue(organization(), "laptop");

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM console_api_key WHERE key_hash = ?",
                Integer.class, issued.secret());

        assertEquals(0, stored, "what is stored is the hash, not the key");
    }

    @Test
    void aRevokedKeyIdentifiesNobody() {
        String organizationId = organization();
        IssuedApiKey issued = keys.issue(organizationId, "laptop");

        assertTrue(keys.revoke(issued.key().id(), organizationId));

        assertEquals(Principal.ANONYMOUS, resolver.resolve(presenting(issued.secret())));
    }

    @Test
    void anotherOrganizationCannotRevokeThisOnesKey() {
        IssuedApiKey issued = keys.issue(organization(), "laptop");

        assertFalse(keys.revoke(issued.key().id(), organization()),
                "the owner is in the WHERE clause, not in a check beforehand");
    }

    @Test
    void anUnknownKeyIsAnonymousRatherThanAnError() {
        assertEquals(Principal.ANONYMOUS, resolver.resolve(presenting("pm_not-a-real-key")));
    }

    @Test
    void aMissingOrMalformedHeaderIsAnonymousToo() {
        assertEquals(Principal.ANONYMOUS, resolver.resolve(new Metadata()));

        Metadata malformed = new Metadata();
        malformed.put(AUTHORIZATION, "pm_no-bearer-prefix");
        assertEquals(Principal.ANONYMOUS, resolver.resolve(malformed));
    }

    @Test
    void usingAKeyRecordsThatItIsInUse() {
        IssuedApiKey issued = keys.issue(organization(), "laptop");

        resolver.resolve(presenting(issued.secret()));

        assertTrue(keys.list(issued.key().organizationId()).get(0).lastUsedAt() != null,
                "which key is still in use is the question anybody revoking one has");
    }

    @Test
    void aListedKeyShowsEnoughToTellItApartAndNoMore() {
        IssuedApiKey issued = keys.issue(organization(), "laptop");

        ApiKey listed = keys.list(issued.key().organizationId()).get(0);

        assertTrue(issued.secret().startsWith(listed.prefix()));
        assertTrue(listed.prefix().length() < issued.secret().length() / 2, listed.prefix());
    }

    @Test
    void revokedKeysAreNotListed() {
        String organizationId = organization();
        IssuedApiKey issued = keys.issue(organizationId, "laptop");
        keys.revoke(issued.key().id(), organizationId);

        assertTrue(keys.list(organizationId).isEmpty());
    }
}
