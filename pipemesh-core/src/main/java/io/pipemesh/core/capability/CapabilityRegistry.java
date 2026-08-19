package io.pipemesh.core.capability;

import java.util.Optional;

/**
 * Resolves the name a workflow used into a registration. This is also where
 * permission enforcement belongs — the registry, not the DSL (§23).
 */
public interface CapabilityRegistry {

    Optional<CapabilityDescriptor> find(CapabilityId id);
}
