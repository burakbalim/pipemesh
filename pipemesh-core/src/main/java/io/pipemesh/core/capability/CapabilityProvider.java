package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How the runtime reaches one class of capability — MCP, REST, gRPC, an
 * in-process function, an external worker (§10).
 *
 * <p>A provider is selected by {@link CapabilityDescriptor#executionType()}.
 * Adding one is a registration concern: no workflow, schema or engine class
 * changes when a new transport appears (§9.8).
 *
 * <p>Invocations are provider I/O and must be made outside any open transaction.
 *
 * <p>{@link CapabilityCall} carries who is asking. Most providers ignore it; one
 * that routes to a remote worker cannot work without it, which is why it is a
 * parameter rather than something a provider has to reach for.
 */
public interface CapabilityProvider {

    /** The {@code execution.type} this provider claims. */
    String type();

    CapabilityResult invoke(CapabilityDescriptor capability, JsonNode input, CapabilityCall call);
}
