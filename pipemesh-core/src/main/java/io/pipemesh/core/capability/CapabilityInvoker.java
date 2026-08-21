package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The one way a capability is reached.
 *
 * <p>Both the capability step and the agent step go through here, which is the
 * point: an agent that could call capabilities by its own route would be a way
 * around the permission check, and a boundary with a second door is not a
 * boundary (§23).
 *
 * <p>Everything a caller must not skip lives here — resolving the registration,
 * checking what the caller holds, picking the provider that serves the
 * registration's transport.
 */
public final class CapabilityInvoker {

    private final CapabilityRegistry capabilities;
    private final Map<String, CapabilityProvider> providers;

    public CapabilityInvoker(CapabilityRegistry capabilities, List<CapabilityProvider> providers) {
        this.capabilities = Objects.requireNonNull(capabilities, "capability registry");
        this.providers = providers.stream().collect(
                Collectors.toUnmodifiableMap(CapabilityProvider::type, provider -> provider));
    }

    public Optional<CapabilityDescriptor> find(CapabilityId id) {
        return capabilities.find(id);
    }

    public CapabilityResult invoke(CapabilityId id, JsonNode input, CapabilityCall call) {
        Optional<CapabilityDescriptor> registration = capabilities.find(id);
        if (registration.isEmpty()) {
            return new CapabilityResult.Failure("capability.unknown",
                    "no capability registered as '" + id + "'", false);
        }
        CapabilityDescriptor capability = registration.get();

        List<String> missing = call.principal().missingFrom(capability.permissions());
        if (!missing.isEmpty()) {
            // Refused, not failed: trying again with the same caller changes
            // nothing, and a retry policy should not spend attempts on it (§23).
            return new CapabilityResult.Failure("capability.forbidden",
                    "'" + call.principal() + "' may not use '" + id + "': missing " + missing, false);
        }

        CapabilityProvider provider = providers.get(capability.executionType());
        if (provider == null) {
            return new CapabilityResult.Failure("capability.no_provider",
                    "capability '" + id + "' needs a '" + capability.executionType()
                            + "' provider, which is not registered", false);
        }

        return retryableOnly(capability, provider.invoke(capability, input, call));
    }

    /**
     * Strips the retryable flag from a capability that declared itself
     * non-idempotent.
     *
     * <p>A transport failure leaves one question unanswered — did the call
     * arrive? — and for a capability that charges a card, retrying on that
     * uncertainty is how a customer gets billed twice (§17).
     */
    private CapabilityResult retryableOnly(CapabilityDescriptor capability, CapabilityResult result) {
        if (!(result instanceof CapabilityResult.Failure failure) || capability.idempotent()) {
            return result;
        }
        return new CapabilityResult.Failure(failure.code(), failure.message(), false);
    }
}
