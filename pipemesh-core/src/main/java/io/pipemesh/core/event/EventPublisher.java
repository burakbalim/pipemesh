package io.pipemesh.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ResumeSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Delivers an event to whatever was waiting for it (§9.7, §28).
 *
 * <p>The publisher does not name an execution — it does not know one. It says
 * what happened and what it happened to, and this finds the executions filed
 * under that.
 *
 * <p>All of them. Two executions waiting on the same order both hear about the
 * payment; stopping at the first would silently abandon the second, and nothing
 * would report it.
 */
public final class EventPublisher {

    private final WaitStore waits;
    private final DefaultWorkflowRuntime runtime;

    public EventPublisher(WaitStore waits, DefaultWorkflowRuntime runtime) {
        this.waits = Objects.requireNonNull(waits, "wait store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * @return the executions this event moved along, which is empty when nobody
     *         was listening. An event with no audience is dropped: keeping it
     *         would mean deciding how long to keep it, and that is a decision
     *         worth making deliberately rather than by accident
     */
    public List<ExecutionHandle> publish(EventKey key, JsonNode payload) {
        List<ExecutionHandle> moved = new ArrayList<>();

        for (PendingWait waiting : waits.waitingFor(key)) {
            // Resumed as the execution's own caller: the publisher is delivering
            // news, not acting on anyone's behalf, and the isolation check has
            // already been made by the key (§22.2).
            moved.add(runtime.resume(
                    waiting.executionId(),
                    new ResumeSignal.Event(waiting.waitId(), key.name(), payload),
                    Principal.SYSTEM));
        }
        return List.copyOf(moved);
    }
}
