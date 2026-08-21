package io.pipemesh.core.capability;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.OrganizationMismatchException;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Labelling is not isolation" (§22.2) — the sentence this closes.
 */
class OrganizationIsolationTest {

    private static final String WORKFLOW = """
            {
              "id": "wait_for_someone", "version": "1.0", "entry": "approval",
              "steps": [
                {"id": "approval", "type": "human_approval", "message": "ok?",
                 "onApproved": "done", "onRejected": "done"},
                {"id": "done", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    private static final Principal ACME =
            Principal.of("burak").belongingTo(OrganizationId.of("acme"));

    private static final Principal RIVAL =
            Principal.of("someone").belongingTo(OrganizationId.of("rival"));

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final DefaultWorkflowRuntime runtime = runtime();

    private DefaultWorkflowRuntime runtime() {
        StepExecutors executors = StepExecutors.of(
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        return new DefaultWorkflowRuntime(
                workflows, stateStore, new WorkflowExecutor(stateStore, executors));
    }

    private ExecutionHandle startAs(Principal caller, String organization) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("secret", "acme's business");
        return runtime.start(new ExecutionRequest(
                WorkflowId.of("wait_for_someone"), new ExecutionInput(input),
                OrganizationId.of(organization), null, null, caller));
    }

    private ExecutionHandle acmeExecution() {
        return startAs(ACME, "acme");
    }

    @Test
    void refusesToShowOneOrganizationsWorkToAnother() {
        ExecutionHandle acme = acmeExecution();

        assertThrows(OrganizationMismatchException.class,
                () -> runtime.snapshot(acme.executionId(), RIVAL));
    }

    @Test
    void refusesToLetAnotherOrganizationMoveItAlong() {
        ExecutionHandle acme = acmeExecution();

        assertThrows(OrganizationMismatchException.class,
                () -> runtime.resume(acme.executionId(),
                        new ResumeSignal.Approval(acme.executionId().value() + ":approval",
                                true, "someone", ""),
                        RIVAL));
    }

    @Test
    void refusesToLetACallerStartWorkAsSomebodyElse() {
        // Not only a data question: worker routing follows the organization.
        assertThrows(OrganizationMismatchException.class, () -> startAs(RIVAL, "acme"));
    }

    @Test
    void letsAnOrganizationReadItsOwn() {
        ExecutionHandle acme = acmeExecution();

        var snapshot = runtime.snapshot(acme.executionId(), ACME).orElseThrow();

        assertEquals(OrganizationId.of("acme"), snapshot.organization());
        assertEquals("acme's business", snapshot.variables().path("input").path("secret").asText());
    }

    @Test
    void letsAnOrganizationResumeItsOwn() {
        ExecutionHandle acme = acmeExecution();

        ExecutionHandle finished = runtime.resume(acme.executionId(),
                new ResumeSignal.Approval(acme.executionId().value() + ":approval", true, "burak", ""),
                ACME);

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
    }

    @Test
    void theSystemCallerIsNotFencedIn() {
        ExecutionHandle acme = acmeExecution();

        assertTrue(runtime.snapshot(acme.executionId(), Principal.SYSTEM).isPresent());
        assertTrue(runtime.snapshot(acme.executionId()).isPresent(), "the plain call is system too");
    }

    @Test
    void aCallerNobodyIdentifiedIsNotFencedInEither() {
        ExecutionHandle acme = acmeExecution();

        // Tenants cannot be kept apart without telling callers apart. A deployment
        // that identifies nobody has no isolation, and this says so rather than
        // pretending to check something it cannot see.
        assertTrue(runtime.snapshot(acme.executionId(), Principal.ANONYMOUS).isPresent());
        assertTrue(Principal.ANONYMOUS.organizationIfKnown().isEmpty());
    }
}
