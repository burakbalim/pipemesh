package io.pipemesh.core.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.StepRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepType;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steps reach out to models, tools and other people's services, and those throw.
 * Letting one escape would lose the execution: the state it reached would never
 * be written and nothing would record why.
 */
class ThrowingStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String WORKFLOW = """
            {
              "id": "fragile", "version": "1.0", "entry": "call",
              "steps": [{"id": "call", "type": "capability", "capability": "x"}]
            }
            """;

    private static final class BrokenStepExecutor implements StepExecutor {

        @Override
        public boolean supports(StepType type) {
            return true;
        }

        @Override
        public StepResult execute(Step step, ExecutionContext context) {
            throw new IllegalStateException("the provider is down");
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    @Test
    void turnsAThrowingStepIntoAFailedStepRatherThanLosingTheExecution() throws Exception {
        StepExecutors executors = StepExecutors.of(new BrokenStepExecutor());

        ExecutionGraph graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(WORKFLOW));

        ExecutionRecord finished = new WorkflowExecutor(stateStore, executors).start(
                graph, ExecutionId.generate(),
                new ExecutionInput((ObjectNode) JSON.readTree("{}")));

        assertEquals(ExecutionStatus.FAILED, finished.status());

        StepRecord failure = stateStore.historyOf(finished.executionId()).get(0);
        assertEquals("step.threw", failure.output().path("code").asText());
        assertTrue(failure.output().path("message").asText().contains("the provider is down"));
    }
}
