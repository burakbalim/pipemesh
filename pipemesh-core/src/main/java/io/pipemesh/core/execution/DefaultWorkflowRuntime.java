package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.capability.Principal;
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
    private final StartMode startMode;

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

        this(workflows, stateStore, executor, intents, StartMode.INLINE);
    }

    /**
     * @param startMode whether {@code start} drives the execution itself or leaves
     *                  it for a dispatcher to claim (§28)
     */
    public DefaultWorkflowRuntime(
            WorkflowRegistry workflows, StateStore stateStore, WorkflowExecutor executor,
            IntentResolver intents, StartMode startMode) {

        this.workflows = Objects.requireNonNull(workflows, "workflow registry");
        this.stateStore = Objects.requireNonNull(stateStore, "state store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.intents = intents;
        this.startMode = Objects.requireNonNull(startMode, "start mode");
    }

    @Override
    public ExecutionHandle start(ExecutionRequest request) {
        // Starting work as somebody else is not only a data question: worker
        // routing follows the organization, so a caller who could name another's
        // would reach their workers too (§14, §22.2).
        refuseIfNotTheirs(request.principal(), request.organization());

        ExecutionGraph graph = graphFor(request);
        ExecutionId executionId = ExecutionId.generate();

        return handleOf(startMode == StartMode.DISPATCHED
                ? executor.create(graph, executionId, request)
                : executor.start(graph, executionId, request));
    }

    /**
     * A pinned request gets exactly that version; an unpinned one gets whatever is
     * newest, chosen here once and then frozen into the record by the executor.
     */
    private ExecutionGraph graphFor(ExecutionRequest request) {
        return request.versionIfPinned()
                .map(version -> workflows.find(request.workflowId(), version)
                        .orElseThrow(() -> new NoSuchElementException(
                                "workflow '" + request.workflowId() + "' has no version '"
                                        + version + "' registered")))
                .orElseGet(() -> workflows.latest(request.workflowId())
                        .orElseThrow(() -> new NoSuchElementException(
                                "no workflow registered as '" + request.workflowId() + "'")));
    }

    @Override
    public ExecutionHandle process(ProcessRequest request) {
        refuseIfNotTheirs(request.principal(), request.organization());

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
                describe(resolved),
                request.principal()));
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
        return resume(executionId, signal, Principal.SYSTEM);
    }

    /** Resumes on behalf of a caller, who must belong where the execution does. */
    public ExecutionHandle resume(ExecutionId executionId, ResumeSignal signal, Principal caller) {
        ExecutionRecord record = load(executionId);
        refuseIfNotTheirs(caller, record.organization());

        if (!record.status().isResumable()) {
            return handleOf(record);
        }
        // The version comes from the record, never from whoever is resuming: an
        // execution finishes inside the graph it started in, and a deploy in the
        // meantime does not get a vote (§24).
        ExecutionGraph graph = workflows.find(record.workflowId(), record.workflowVersion())
                .orElseThrow(() -> new NoSuchElementException(
                        "execution " + executionId + " refers to unregistered workflow '"
                                + record.workflowId() + "@" + record.workflowVersion() + "'"));

        return handleOf(executor.resume(graph, record, signal));
    }

    @Override
    public Optional<ExecutionSnapshot> snapshot(ExecutionId executionId) {
        return snapshot(executionId, Principal.SYSTEM);
    }

    /**
     * Reads an execution on behalf of a caller.
     *
     * <p>Variables carry whatever the workflow's steps wrote into them, which is
     * business data. Handing that to whoever knows an id is the leak §22.2 warned
     * about when it said labelling is not isolation.
     */
    public Optional<ExecutionSnapshot> snapshot(ExecutionId executionId, Principal caller) {
        return stateStore.find(executionId)
                .map(record -> {
                    refuseIfNotTheirs(caller, record.organization());
                    return snapshotOf(record);
                });
    }

    /**
     * Lets a caller through unless it is known to belong somewhere else.
     *
     * <p>A caller nobody identified has no organization, and nothing can be
     * enforced for it — a deployment without a principal resolver has no tenant
     * isolation, which is a property of not authenticating anyone rather than
     * something this could fix.
     */
    private void refuseIfNotTheirs(Principal caller, OrganizationId owner) {
        if (caller.unrestricted()) {
            return;
        }
        caller.organizationIfKnown().ifPresent(mine -> {
            if (!mine.equals(owner)) {
                throw new OrganizationMismatchException(caller, owner);
            }
        });
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
