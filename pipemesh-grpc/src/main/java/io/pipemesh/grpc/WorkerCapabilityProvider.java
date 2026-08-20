package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.capability.CapabilityCall;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityResult;
import io.pipemesh.proto.v1.CapabilityInvocation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reaches a capability that lives inside somebody's application.
 *
 * <p>One {@link CapabilityProvider} among others. A registration like:
 *
 * <pre>
 * "execution": { "type": "worker", "capability": "calculate_discount" }
 * </pre>
 *
 * <p>is invoked from a workflow that says only {@code "capability":
 * "calculate_discount"} — the same sentence it would use for an MCP tool. That
 * indistinguishability is the point (§9.8).
 */
public final class WorkerCapabilityProvider implements CapabilityProvider {

    public static final String TYPE = "worker";

    /**
     * How long to wait when the step declared no timeout of its own.
     *
     * <p>A step's {@code timeout} policy is authoritative, and the engine applies
     * it. This exists for the workflow that set none: without it a worker that
     * accepted a call and then stopped responding would hold an execution
     * forever, and nothing would say why.
     */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(60);

    private final WorkerRegistry workers;
    private final Duration deadline;

    public WorkerCapabilityProvider(WorkerRegistry workers) {
        this(workers, DEFAULT_DEADLINE);
    }

    public WorkerCapabilityProvider(WorkerRegistry workers, Duration deadline) {
        this.workers = Objects.requireNonNull(workers, "worker registry");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public CapabilityResult invoke(
            CapabilityDescriptor capability, JsonNode input, CapabilityCall call) {

        String capabilityId = capability.execution().path("capability").asText(capability.id().value());

        Optional<ConnectedWorker> worker =
                workers.pick(call.organization().value(), capabilityId);

        if (worker.isEmpty()) {
            // Retryable so the step's own retry policy covers a worker restart,
            // rather than this inventing a wait nobody asked for. Blocking until
            // one appears would look like a hang from the caller's side.
            return new CapabilityResult.Failure("worker.none_connected",
                    "no worker in organization '" + call.organization()
                            + "' serves capability '" + capabilityId + "'", true);
        }

        return worker.get().invoke(invocationFor(capabilityId, input, call), deadline);
    }

    /**
     * A worker function takes named arguments, and a workflow may hand over a
     * bare string — the same wrapping the MCP provider does, for the same reason.
     */
    private JsonNode asObject(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        if (input.isObject()) {
            return input;
        }
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .set("input", input);
    }

    private CapabilityInvocation invocationFor(
            String capabilityId, JsonNode input, CapabilityCall call) {

        CapabilityInvocation.Builder invocation = CapabilityInvocation.newBuilder()
                .setInvocationId(UUID.randomUUID().toString())
                .setCapabilityId(capabilityId);

        invocation.setInput(JsonStructs.toStruct(asObject(input)));
        call.traceParentIfAny().ifPresent(invocation::setTraceparent);
        return invocation.build();
    }
}
