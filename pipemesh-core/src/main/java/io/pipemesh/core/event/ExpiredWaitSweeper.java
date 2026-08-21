package io.pipemesh.core.event;

import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.ResumeSignal;

import java.time.Clock;
import java.util.Objects;

/**
 * Moves along executions whose wait ran out.
 *
 * <p>An execution that waits forever for something that is never coming is the
 * same failure as a loop with no bound or a call with no deadline — it is simply
 * quieter, because nothing reports a workflow that is merely still waiting.
 *
 * <p>It implements {@link RecoveryScheduler.RecoveryPass}, so the schedule that
 * already sweeps for orphans sweeps for this too. A second timer would have been
 * a second thing to remember to start.
 */
public final class ExpiredWaitSweeper implements RecoveryScheduler.RecoveryPass {

    public static final int DEFAULT_BATCH = 50;

    private final WaitStore waits;
    private final DefaultWorkflowRuntime runtime;
    private final Clock clock;
    private final int batch;

    public ExpiredWaitSweeper(WaitStore waits, DefaultWorkflowRuntime runtime) {
        this(waits, runtime, Clock.systemUTC(), DEFAULT_BATCH);
    }

    public ExpiredWaitSweeper(
            WaitStore waits, DefaultWorkflowRuntime runtime, Clock clock, int batch) {

        this.waits = Objects.requireNonNull(waits, "wait store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batch = batch;
    }

    @Override
    public int sweep() {
        int expired = 0;
        for (PendingWait wait : waits.expiredBy(clock.millis(), batch)) {
            runtime.resume(wait.executionId(),
                    new ResumeSignal.Expired(wait.waitId()), Principal.SYSTEM);
            expired++;
        }
        return expired;
    }
}
