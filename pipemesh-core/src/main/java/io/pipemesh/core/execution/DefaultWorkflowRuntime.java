package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.intent.IntentResolver;
import io.pipemesh.core.intent.IntentUnresolvedException;
import io.pipemesh.core.intent.ResolvedIntent;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.WorkflowId;
import io.pipemesh.core.workflow.WorkflowRegistry;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The in-process binding of the runtime API (§26.1).
 *
 * <p>It holds no execution in memory. Everything it needs to answer a call comes
 * from the state store, which is why a second instance — a different process, a
 * restarted one — can pick up an execution this one started.
 */
public final class DefaultWorkflowRuntime implements WorkflowRuntime {

    private final WorkflowRegistry workflows;
    private final StateStore stateStore;
    private final WorkflowExecutor executor;
    private final IntentResolver intents;

    public DefaultWorkflowRuntime(
            WorkflowRegistry workflows, StateStore stateStore, WorkflowExecutor executor) {
        this(workflows, stateStore, executor, null);
    }

    /**
     * @param intents how a natural-language message is turned into a workflow, or
     *                {@code null} for a runtime that only runs workflows it is
     *                told the name of. Absent is a limit, not a fault — plenty of
     *                deployments never need a message read.
     */
    public DefaultWorkflowRuntime(
            WorkflowRegistry workflows, StateStore stateStore, WorkflowExecutor executor,
            IntentResolver intents) {

        this.workflows = Objects.requireNonNull(workflows, "workflow registry");
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.intents = intents;
    }

    @Override
    public ExecutionHandle start(ExecutionRequest request) {
        ExecutionGraph graph = workflows.find(request.workflowId()).orElseThrow(
                () -> new NoSuchElementException(
                        "no workflow registered as '" + request.workflowId() + "'"));

        return handleOf(executor.start(graph, ExecutionId.generate(), request));
    }

    @Override
    public ExecutionHandle process(ProcessRequest request) {
        if (intents == null) {
            throw new IntentUnresolvedException(
                    "this runtime has no intent resolution; name a workflow and use start()");
        }

        ResolvedIntent resolved = intents.resolve(request.message());

        // Two steps, plainly: the resolver named a workflow, and now the engine
        // runs it. Nothing the resolver said reaches any further than this line.
        return start(new ExecutionRequest(
                resolved.workflow(),
                request.input(),
                request.organization(),
                request.traceParent(),
                describe(resolved)));
    }

    /** What the execution should be able to say about why it ran. */
    private ObjectNode describe(ResolvedIntent resolved) {
        ObjectNode intent = JsonNodeFactory.instance.objectNode()
                .put("id", resolved.intent().value())
                .put("resolvedBy", resolved.source().name().toLowerCase());

        resolved.modelIfAny().ifPresent(model -> intent
                .put("model", model)
                .put("promptVersion", resolved.promptVersion())
                .put("confidence", resolved.confidence()));

        return intent;
    }

    /**
     * Applies a decision to a waiting execution.
     *
     * <p>Delivering the same decision twice is not an error and does not advance
     * the execution twice: the second call finds a status that is no longer
     * resumable and returns where things stand. Two calls racing each other are
     * settled by the store's version check — one wins, the other is rejected as
     * stale rather than silently applied.
     */
    @Override
    public ExecutionHandle resume(ExecutionId executionId, ResumeSignal signal) {
        ExecutionRecord record = load(executionId);
        if (!record.status().isResumable()) {
            return handleOf(record);
        }
        ExecutionGraph graph = workflows.find(record.workflowId()).orElseThrow(
                () -> new NoSuchElementException(
                        "execution " + executionId + " refers to unregistered workflow '"
                                + record.workflowId() + "'"));

        return handleOf(executor.resume(graph, record, signal));
    }

    @Override
    public Optional<ExecutionSnapshot> snapshot(ExecutionId executionId) {
        return stateStore.find(executionId).map(this::snapshotOf);
    }

    private ExecutionRecord load(ExecutionId executionId) {
        return stateStore.find(executionId).orElseThrow(
                () -> new NoSuchElementException("no execution " + executionId));
    }

    private ExecutionHandle handleOf(ExecutionRecord record) {
        return new ExecutionHandle(record.executionId(), record.status(), record.currentStep());
    }

    private ExecutionSnapshot snapshotOf(ExecutionRecord record) {
        return new ExecutionSnapshot(
                record.executionId(),
                record.organization(),
                record.workflowId(),
                record.workflowVersion(),
                record.status(),
                record.currentStep(),
                record.variables(),
                record.createdAtEpochMillis(),
                record.updatedAtEpochMillis());
    }
}
