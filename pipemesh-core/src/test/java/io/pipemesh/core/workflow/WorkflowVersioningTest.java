package io.pipemesh.core.workflow;

import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.ExecutionHandle;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.core.execution.ResumeSignal;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An execution finishes in the graph it started in. A deploy that happens while
 * it is suspended does not get a vote (§24).
 */
class WorkflowVersioningTest {

    /** v1 approves into a step that v2 no longer has. */
    private static final String REVIEW_V1 = """
            {
              "id": "review", "version": "1.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "ok?",
                 "onApproved": "publish_v1", "onRejected": "drop"},
                {"id": "publish_v1", "type": "terminal", "status": "COMPLETED"},
                {"id": "drop", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private static final String REVIEW_V2 = """
            {
              "id": "review", "version": "2.0", "entry": "decide",
              "steps": [
                {"id": "decide", "type": "human_approval", "message": "still ok?",
                 "onApproved": "publish_v2", "onRejected": "drop"},
                {"id": "publish_v2", "type": "terminal", "status": "CANCELLED"},
                {"id": "drop", "type": "terminal", "status": "CANCELLED"}
              ]
            }
            """;

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final InMemoryApprovalStore approvals = new InMemoryApprovalStore();
    private final StepExecutors executors = StepExecutors.of(
            new ApprovalStepExecutor(approvals), new TerminalStepExecutor());
    private final InMemoryWorkflowRegistry registry =
            new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
    private final DefaultWorkflowRuntime runtime = new DefaultWorkflowRuntime(
            registry, stateStore, new WorkflowExecutor(stateStore, executors));

    private ExecutionGraph register(String definition) {
        return registry.register(new WorkflowDefinitionReader().read(definition));
    }

    private ExecutionHandle start(ExecutionRequest request) {
        return runtime.start(request);
    }

    private ExecutionRequest review() {
        return new ExecutionRequest(
                WorkflowId.of("review"), ExecutionInput.empty(), OrganizationId.of("acme"), null);
    }

    private ExecutionHandle approve(ExecutionHandle waiting) {
        String approvalId = approvals.pendingFor(waiting.executionId()).get(0).approvalId();
        return runtime.resume(
                waiting.executionId(),
                new ResumeSignal.Approval(approvalId, true, "someone", null),
                Principal.SYSTEM);
    }

    @Test
    void aNewVersionDoesNotDisplaceTheOldOne() {
        ExecutionGraph first = register(REVIEW_V1);
        ExecutionGraph second = register(REVIEW_V2);

        assertNotSame(first, second);
        assertSame(first, registry.find(WorkflowId.of("review"), WorkflowVersion.of("1.0")).orElseThrow());
        assertSame(second, registry.find(WorkflowId.of("review"), WorkflowVersion.of("2.0")).orElseThrow());
    }

    @Test
    void startingWithoutAVersionTakesTheNewestAndWritesItDown() {
        register(REVIEW_V1);
        register(REVIEW_V2);

        ExecutionHandle handle = start(review());

        assertEquals(WorkflowVersion.of("2.0"),
                runtime.snapshot(handle.executionId()).orElseThrow().workflowVersion());
    }

    @Test
    void startingWithAVersionRunsExactlyThatOne() {
        register(REVIEW_V1);
        register(REVIEW_V2);

        ExecutionHandle handle = start(review().pinnedTo(WorkflowVersion.of("1.0")));

        assertEquals(WorkflowVersion.of("1.0"),
                runtime.snapshot(handle.executionId()).orElseThrow().workflowVersion());
    }

    @Test
    void anUnregisteredVersionIsRefusedByVersionNotById() {
        register(REVIEW_V1);

        Exception refused = assertThrows(RuntimeException.class,
                () -> start(review().pinnedTo(WorkflowVersion.of("3.0"))));

        assertTrue(refused.getMessage().contains("3.0"),
                "the message must name the version, not just the workflow: " + refused.getMessage());
    }

    /** The whole point: a deploy lands while somebody is still deciding. */
    @Test
    void anExecutionSuspendedOnTheOldVersionResumesOnTheOldVersion() {
        register(REVIEW_V1);
        ExecutionHandle waiting = start(review());
        assertEquals(ExecutionStatus.WAITING, waiting.status());

        register(REVIEW_V2);

        ExecutionHandle finished = approve(waiting);

        assertEquals(ExecutionStatus.COMPLETED, finished.status(),
                "v2 approves into a CANCELLED terminal; COMPLETED means v1 ran");
    }

    @Test
    void versionsAreOrderedAsNumbersNotAsText() {
        assertTrue(WorkflowVersion.of("10.0").compareTo(WorkflowVersion.of("9.0")) > 0);
        assertTrue(WorkflowVersion.of("1.2").compareTo(WorkflowVersion.of("1.10")) < 0);
        assertEquals(0, WorkflowVersion.of("1.0").compareTo(WorkflowVersion.of("1.0.0")));
    }

    @Test
    void latestUsesThatOrdering() {
        register(REVIEW_V1.replace("\"version\": \"1.0\"", "\"version\": \"9.0\""));
        register(REVIEW_V2.replace("\"version\": \"2.0\"", "\"version\": \"10.0\""));

        assertEquals(WorkflowVersion.of("10.0"),
                registry.latest(WorkflowId.of("review")).orElseThrow().version());
    }

    @Test
    void aVersionThatCannotBeOrderedIsRefusedWhenItIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowVersion.of("1.0-beta"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowVersion.of("latest"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowVersion.of("-1"));
    }

    @Test
    void registeringTheSameVersionAgainIsFineWhenNothingChanged() {
        ExecutionGraph first = register(REVIEW_V1);

        assertSame(first, register(REVIEW_V1), "reading the same directory twice is not an error");
    }

    @Test
    void registeringTheSameVersionWithADifferentDefinitionIsRefused() {
        register(REVIEW_V1);

        Exception refused = assertThrows(IllegalStateException.class,
                () -> register(REVIEW_V1.replace("\"ok?\"", "\"changed\"")));

        assertTrue(refused.getMessage().contains("review@1.0"), refused.getMessage());
    }

    @Test
    void identityIsTheStringEvenWhenOrderingCallsThemEqual() {
        register(REVIEW_V1);
        register(REVIEW_V1.replace("\"version\": \"1.0\"", "\"version\": \"1.0.0\""));

        assertEquals(List.of(true, true), List.of(
                registry.find(WorkflowId.of("review"), WorkflowVersion.of("1.0")).isPresent(),
                registry.find(WorkflowId.of("review"), WorkflowVersion.of("1.0.0")).isPresent()));
    }
}
