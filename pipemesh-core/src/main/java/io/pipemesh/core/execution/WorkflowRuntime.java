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

    ExecutionHandle resume(ExecutionId executionId, ResumeSignal signal);

    Optional<ExecutionSnapshot> snapshot(ExecutionId executionId);
}
