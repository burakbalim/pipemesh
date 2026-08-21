package io.pipemesh.core.dispatch;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How runtime instances divide executions between them without a dispatcher
 * (§28). Work is not assigned; it is taken.
 *
 * <p>A central assigner would be both a single point of failure and a second
 * answer to "what is runnable" — the rows already know. Each instance asks for
 * some, and the store settles who got what.
 *
 * <p><b>An expired lease does not mean the owner is dead.</b> It means the owner
 * stopped renewing, which a long step looks exactly like. So this improves the
 * guess {@code RecoverySweeper} already makes, and does not replace either safety
 * net behind it: the version check means only one writer can advance an
 * execution, and {@code StepExecutor.repeatable} means a step that may already
 * have had an effect is never run twice.
 */
public interface ExecutionLeases {

    /**
     * Takes up to {@code limit} executions that nobody is driving.
     *
     * <p>Only executions that can move on their own are eligible — one waiting for
     * a person or an event is not stuck, it is waiting, and claiming it would
     * occupy a driver with something that cannot progress.
     *
     * @param limit caps one round so a single instance cannot pull the whole queue
     */
    List<ExecutionLease> claim(String owner, Duration duration, int limit);

    /**
     * Extends a lease this owner still holds.
     *
     * @return the extended lease, or empty if it was already taken by somebody
     *         else — the caller has lost the execution and must stop driving it
     */
    Optional<ExecutionLease> renew(ExecutionLease lease, Duration duration);

    /** Gives an execution back, whether it finished or the driver gave up. */
    void release(ExecutionLease lease);
}
