package io.pipemesh.core.parallel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.execution.step.TransformStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryStateStore stateStore = new InMemoryStateStore();

    private ExecutionRecord run(String operation, String inputs, String input) {
        StepExecutors executors = StepExecutors.of(
                new TransformStepExecutor(), new TerminalStepExecutor());

        String workflow = """
                {
                  "id": "shape_it", "version": "1.0", "entry": "combine",
                  "steps": [
                    {"id": "combine", "type": "transform", "operation": "%s",
                     "inputs": %s, "output": "result", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """.formatted(operation, inputs);

        var graph = new InMemoryWorkflowRegistry(new WorkflowCompiler(executors))
                .register(new WorkflowDefinitionReader().read(workflow));

        try {
            return new WorkflowExecutor(stateStore, executors).start(
                    graph, ExecutionId.generate(),
                    ExecutionRequest.of(WorkflowId.of("shape_it"),
                            new ExecutionInput((ObjectNode) JSON.readTree(input))));
        } catch (Exception malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    @Test
    void mergesObjectsWithLaterInputsWinning() {
        ExecutionRecord finished = run("merge", "[\"$.input.a\", \"$.input.b\"]",
                "{\"a\":{\"x\":1,\"shared\":\"from a\"},\"b\":{\"y\":2,\"shared\":\"from b\"}}");

        var result = finished.variables().path("result");
        assertEquals(1, result.path("x").asInt());
        assertEquals(2, result.path("y").asInt());
        assertEquals("from b", result.path("shared").asText());
    }

    @Test
    void picksTheFirstOneThatIsActuallyThere() {
        ExecutionRecord finished = run("pick", "[\"$.input.missing\", \"$.input.second\"]",
                "{\"second\":\"used this\"}");

        assertEquals("used this", finished.variables().path("result").asText());
    }

    @Test
    void collectsEverythingInTheOrderAsked() {
        ExecutionRecord finished = run("collect", "[\"$.input.b\", \"$.input.a\"]",
                "{\"a\":1,\"b\":2}");

        var result = finished.variables().path("result");
        assertEquals(2, result.get(0).asInt());
        assertEquals(1, result.get(1).asInt());
    }

    @Test
    void keepsAnAbsentValueVisibleWhenCollecting() {
        ExecutionRecord finished = run("collect", "[\"$.input.a\", \"$.input.gone\"]", "{\"a\":1}");

        assertEquals(2, finished.variables().path("result").size(),
                "dropping it silently would shift everything after it");
        assertTrue(finished.variables().path("result").get(1).isNull());
    }

    @Test
    void asksForNothingItCannotDo() {
        ExecutionRecord finished = run("summarise", "[\"$.input.a\"]", "{\"a\":1}");

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("transform.unknown_operation",
                stateStore.historyOf(finished.executionId()).get(0).output().path("code").asText());
    }
}
