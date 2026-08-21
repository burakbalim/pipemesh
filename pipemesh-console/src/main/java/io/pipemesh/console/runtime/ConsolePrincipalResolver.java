package io.pipemesh.console.runtime;

import io.grpc.Metadata;
import io.pipemesh.console.apikey.ApiKeyService;
import io.pipemesh.console.apikey.KeyHolder;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.grpc.PrincipalResolver;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Turns an API key into the caller the runtime reasons about (§23).
 *
 * <p>This is the answer {@code PrincipalResolver} has been documented as waiting
 * for since the gRPC boundary was built: the runtime cannot authenticate anyone
 * because it has no idea what a valid token looks like in a given deployment.
 * Here it does — a key the console issued, hashed and looked up.
 *
 * <p>With this in place, §22.2's caveat stops applying to this deployment. Every
 * execution now carries a real organization, so the isolation the runtime has
 * always enforced finally has something real to enforce it against.
 */
@Component
public class ConsolePrincipalResolver implements PrincipalResolver {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER = "Bearer ";

    private final ApiKeyService keys;

    public ConsolePrincipalResolver(ApiKeyService keys) {
        this.keys = keys;
    }

    /**
     * Anonymous when the header is missing, malformed, unknown or revoked — the
     * four cases are deliberately indistinguishable from out here.
     *
     * <p>Anonymous is not an error: a capability that asks for no permission
     * still runs, and the refusal happens where the requirement is, which is the
     * only place that knows what was actually needed.
     */
    @Override
    public Principal resolve(Metadata metadata) {
        String presented = bearerToken(metadata);
        if (presented == null) {
            return Principal.ANONYMOUS;
        }

        return keys.holderOf(presented)
                .map(this::principalOf)
                .orElse(Principal.ANONYMOUS);
    }

    private Principal principalOf(KeyHolder holder) {
        // Last-used is recorded because "which of these keys is still in use" is
        // the question anybody revoking one actually has.
        keys.markUsed(holder.keyId());

        return new Principal(
                holder.keyId(),
                Set.copyOf(holder.permissions()),
                false,
                OrganizationId.of(holder.organizationId()));
    }

    private static String bearerToken(Metadata metadata) {
        String header = metadata.get(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
