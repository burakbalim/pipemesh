package io.pipemesh.core.capability;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds capability registrations in memory.
 *
 * <p>This is where ownership, version and permissions live — everything a
 * workflow deliberately cannot see (§9.8, §23).
 */
public final class InMemoryCapabilityRegistry implements CapabilityRegistry {

    private final Map<CapabilityId, CapabilityDescriptor> capabilities = new ConcurrentHashMap<>();

    public InMemoryCapabilityRegistry register(CapabilityDescriptor descriptor) {
        capabilities.put(Objects.requireNonNull(descriptor, "descriptor").id(), descriptor);
        return this;
    }

    @Override
    public Optional<CapabilityDescriptor> find(CapabilityId id) {
        return Optional.ofNullable(capabilities.get(id));
    }
}
