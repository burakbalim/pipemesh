package io.pipemesh.core.execution;

import io.pipemesh.core.workflow.StepId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepResultTest {

    @Test
    void terminateRejectsANonTerminalStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new StepResult.Terminate(ExecutionStatus.RUNNING));
    }

    @Test
    void terminateAcceptsATerminalStatus() {
        assertTrue(new StepResult.Terminate(ExecutionStatus.COMPLETED).status().isTerminal());
    }

    @Test
    void continueDefaultsToNoVariables() {
        assertTrue(StepResult.Continue.to(StepId.of("next")).variables().isEmpty());
    }

    @Test
    void suspendWithoutATimeoutIsAllowed() {
        StepResult.Suspend suspend = new StepResult.Suspend(
                new SuspensionReason(SuspensionReason.APPROVAL, null), null);

        assertTrue(suspend.timeoutIfAny().isEmpty());
    }

    @Test
    void onlyWaitingExecutionsAreResumable() {
        assertTrue(ExecutionStatus.WAITING.isResumable());
        assertFalse(ExecutionStatus.RUNNING.isResumable());
        assertFalse(ExecutionStatus.COMPLETED.isResumable());
    }
}
