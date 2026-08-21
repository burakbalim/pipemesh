package io.pipemesh.core.execution;

/**
 * Who runs a newly started execution (§28).
 *
 * <p>A deployment-shaped choice, kept as one readable word rather than a boolean
 * on a constructor: the difference is visible to every caller of {@code start},
 * so it should be visible where the runtime is built.
 */
public enum StartMode {

    /**
     * The calling thread drives the execution until it stops moving. What a
     * single process wants, and the default — nobody running one instance should
     * have to stand up a dispatcher to see a workflow run.
     */
    INLINE,

    /**
     * The execution is written down and left for a dispatcher to claim, so
     * {@code start} returns immediately with a {@code CREATED} handle.
     *
     * <p>Requires that something is actually dispatching. A runtime in this mode
     * with no {@link io.pipemesh.core.dispatch.ExecutionDispatcher} anywhere
     * accepts work and never runs it.
     */
    DISPATCHED
}
