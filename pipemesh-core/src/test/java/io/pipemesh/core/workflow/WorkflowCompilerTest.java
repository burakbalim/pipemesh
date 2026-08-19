package io.pipemesh.core.workflow;

import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCompilerTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(
            StepExecutors.of(new ConditionStepExecutor(), new TerminalStepExecutor()));

    private final WorkflowDefinitionReader reader = new WorkflowDefinitionReader();

    private ExecutionGraph compile(String json) {
        return compiler.compile(reader.read(json));
    }

    private WorkflowCompilationException problemsOf(String json) {
        return assertThrows(WorkflowCompilationException.class, () -> compile(json));
    }

    @Test
    void compilesAValidWorkflow() {
        ExecutionGraph graph = compile("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.input.ok == true",
                     "onTrue": "done", "onFalse": "rejected"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"},
                    {"id": "rejected", "type": "terminal", "status": "CANCELLED"}
                  ]
                }
                """);

        assertEquals(StepId.of("validate"), graph.entry());
        assertEquals(3, graph.steps().size());
    }

    @Test
    void rejectsAnEntryThatDoesNotExist() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "nowhere",
                  "steps": [{"id": "done", "type": "terminal", "status": "COMPLETED"}]
                }
                """);

        assertTrue(failure.problems().stream().anyMatch(p -> p.contains("entry step 'nowhere'")));
    }

    @Test
    void rejectsAnEdgePointingAtAMissingStep() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.a == 1",
                     "onTrue": "done", "onFalse": "typo"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertTrue(failure.problems().stream().anyMatch(p -> p.contains("missing step 'typo'")));
    }

    @Test
    void rejectsAnUnreachableStep() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "done",
                  "steps": [
                    {"id": "done", "type": "terminal", "status": "COMPLETED"},
                    {"id": "orphan", "type": "terminal", "status": "CANCELLED"}
                  ]
                }
                """);

        assertTrue(failure.problems().stream().anyMatch(p -> p.contains("'orphan' is unreachable")));
    }

    @Test
    void rejectsAStepTypeNoExecutorClaims() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "guess",
                  "steps": [{"id": "guess", "type": "telepathy"}]
                }
                """);

        assertTrue(failure.problems().stream().anyMatch(p -> p.contains("unknown type 'telepathy'")));
    }

    @Test
    void rejectsDuplicateStepIds() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "done",
                  "steps": [
                    {"id": "done", "type": "terminal", "status": "COMPLETED"},
                    {"id": "done", "type": "terminal", "status": "CANCELLED"}
                  ]
                }
                """);

        assertTrue(failure.problems().stream().anyMatch(p -> p.contains("duplicate step id")));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        WorkflowCompilationException failure = problemsOf("""
                {
                  "id": "check", "version": "1.0", "entry": "validate",
                  "steps": [
                    {"id": "validate", "type": "condition", "expression": "$.a == 1",
                     "onTrue": "typo", "onFalse": "alsoTypo"},
                    {"id": "orphan", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(3, failure.problems().size(), failure.problems().toString());
    }

    @Test
    void acceptsACycleBecauseARetryLoopIsLegitimate() {
        ExecutionGraph graph = compile("""
                {
                  "id": "loop", "version": "1.0", "entry": "again",
                  "steps": [
                    {"id": "again", "type": "condition", "expression": "$.a == 1",
                     "onTrue": "again", "onFalse": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(2, graph.steps().size());
    }
}
