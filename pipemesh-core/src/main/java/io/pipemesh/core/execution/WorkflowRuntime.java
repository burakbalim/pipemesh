package io.pipemesh.core.execution;

import java.util.Optional;

/**
 * The public entry point of the runtime.
 *
 * <p>Neither method blocks on a long wait. An execution that reaches an approval
 * is persisted and the calling thread returns (§16); it continues later through
 * {@link #resume}.
 *
 * <p>Every argument and return value here is serializable by construction — this
 * interface is the in-process binding of {@code pipemesh.proto}, not a separate
 * API with its own semantics (§26.1).
 */
public interface WorkflowRuntime {

    ExecutionHandle start(ExecutionRequest request);

    /**
     * Reads a message, picks the workflow it asks for, and starts that (§19).
     *
     * <p>Two steps, in this order and no other: resolution names a workflow, the
     * engine runs it. A runtime that let the reading of a message reach into how
     * the workflow proceeds would have handed control of the application to the
     * thing that was only supposed to answer one question (§20, §37).
     *
     * @throws io.pipemesh.core.intent.IntentUnresolvedException when the message
     *         does not settle on an intent — saying no beats running the nearest
     *         workflow to what someone might have meant
     */
    ExecutionHandle process(ProcessRequest request);

    ExecutionHandle resume(ExecutionId executionId, ResumeSignal signal);

    Optional<ExecutionSnapshot> snapshot(ExecutionId executionId);
}
