package io.pipemesh.core.parallel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.pipemesh.core.execution.ExecutionContext;
import io.pipemesh.core.execution.ExecutionId;
import io.pipemesh.core.execution.ExecutionInput;
import io.pipemesh.core.execution.ExecutionRequest;
import io.pipemesh.core.execution.ExecutionStatus;
import io.pipemesh.core.execution.StepExecutor;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.StepResult;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.ParallelStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.state.ExecutionRecord;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import java.util.Optional;
import io.pipemesh.core.workflow.WorkflowVersion;
import io.pipemesh.core.workflow.ExecutionGraph;
import io.pipemesh.core.workflow.WorkflowRegistry;
import io.pipemesh.core.workflow.Step;
import io.pipemesh.core.workflow.StepId;
import io.pipemesh.core.workflow.StepType;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent work, at the same time, joined once (§9.5, §29). */
class ParallelStepTest {

    /** A step that sleeps, writes one variable and moves on. */
    private static final class SlowStep implements StepExecutor {

        @Override
        public boolean supports(StepType type) {
            return StepType.of("slow").equals(type);
        }

        @Override
        public StepResult execute(Step step, ExecutionContext context) {
            long millis = step.config().path("millis").asLong(0);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            String output = step.config().path("output").asText("out");
            String next = step.config().path("next").asText("");

            if (step.config().path("fail").asBoolean(false)) {
                return new StepResult.Failed("slow.broke", "this branch broke", false);
            }
            return new StepResult.Continue(StepId.of(next),
                    Map.of(output, JsonNodeFactory.instance.textNode(step.id().value())));
        }

        @Override
        public List<StepId> outgoing(Step step) {
            String next = step.config().path("next").asText("");
            return next.isBlank() ? List.of() : List.of(StepId.of(next));
        }

        @Override
        public boolean repeatable(Step step, ExecutionContext context) {
            return !step.config().path("dangerous").asBoolean(false);
        }
    }

    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private final AtomicReference<StepExecutors> executors = new AtomicReference<>();

    /**
     * Wires the executors the way an application would.
     *
     * <p>The knot is real: the compiler needs the executors, the parallel executor
     * needs the registry, and the registry needs the compiler. It is tied with two
     * lazy references rather than an initialisation order nobody can follow.
     */
    /** Hands the parallel step a registry that does not exist yet at wiring time. */
    private static WorkflowRegistry lazily(AtomicReference<InMemoryWorkflowRegistry> registry) {
        return new WorkflowRegistry() {
            @Override
            public Optional<ExecutionGraph> find(WorkflowId id, WorkflowVersion version) {
                return registry.get().find(id, version);
            }

            @Override
            public Optional<ExecutionGraph> latest(WorkflowId id) {
                return registry.get().latest(id);
            }
        };
    }

    private ExecutionRecord run(String workflow) {
        AtomicReference<InMemoryWorkflowRegistry> registry = new AtomicReference<>();

        StepExecutors all = StepExecutors.of(
                new SlowStep(),
                new ParallelStepExecutor(lazily(registry), executors::get),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        executors.set(all);
        registry.set(new InMemoryWorkflowRegistry(new WorkflowCompiler(all)));

        var graph = registry.get().register(new WorkflowDefinitionReader().read(workflow));

        return new WorkflowExecutor(stateStore, all).start(
                graph, ExecutionId.generate(),
                ExecutionRequest.of(WorkflowId.of("fan_out"), ExecutionInput.empty()));
    }

    private String failureCode(ExecutionRecord record) {
        var history = stateStore.historyOf(record.executionId());
        return history.get(history.size() - 1).output().path("code").asText();
    }

    private static String twoBranches(String extra) {
        return """
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel",
                     "branches": ["left", "right"], "join": "done"},
                    {"id": "left",  "type": "slow", "millis": 150, "output": "l", "next": "done"%s},
                    {"id": "right", "type": "slow", "millis": 150, "output": "r", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """.formatted(extra);
    }

    @Test
    void bothBranchesLandAfterTheJoin() {
        ExecutionRecord finished = run(twoBranches(""));

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals("left", finished.variables().path("l").asText());
        assertEquals("right", finished.variables().path("r").asText());
    }

    @Test
    void theyRunAtTheSameTime() {
        long before = System.currentTimeMillis();
        run(twoBranches(""));
        long took = System.currentTimeMillis() - before;

        assertTrue(took < 250,
                "two 150ms branches took " + took + "ms; concurrently they should not add up");
    }

    @Test
    void aBranchCanBeMoreThanOneStep() {
        ExecutionRecord finished = run("""
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel", "branches": ["left", "right"], "join": "done"},
                    {"id": "left",  "type": "slow", "millis": 1, "output": "l", "next": "left2"},
                    {"id": "left2", "type": "slow", "millis": 1, "output": "l2", "next": "done"},
                    {"id": "right", "type": "slow", "millis": 1, "output": "r", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(ExecutionStatus.COMPLETED, finished.status());
        assertEquals("left2", finished.variables().path("l2").asText());
    }

    @Test
    void aBrokenBranchBreaksTheStepAndSaysWhichOne() {
        ExecutionRecord finished = run(twoBranches(", \"fail\": true"));

        assertEquals(ExecutionStatus.FAILED, finished.status());

        String message = stateStore.historyOf(finished.executionId())
                .get(0).output().path("message").asText();
        assertTrue(message.contains("left"), message);
    }

    @Test
    void refusesABranchThatStopsToWaitForSomebody() {
        ExecutionRecord finished = run("""
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel", "branches": ["left", "right"], "join": "done"},
                    {"id": "left", "type": "human_approval", "message": "wait",
                     "onApproved": "done", "onRejected": "done"},
                    {"id": "right", "type": "slow", "millis": 1, "output": "r", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("parallel.branch_suspended", failureCode(finished));
    }

    @Test
    void refusesABranchThatEndsTheExecutionInstead() {
        ExecutionRecord finished = run("""
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel", "branches": ["left", "right"], "join": "done"},
                    {"id": "left", "type": "terminal", "status": "CANCELLED"},
                    {"id": "right", "type": "slow", "millis": 1, "output": "r", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("parallel.branch_escaped", failureCode(finished));
    }

    @Test
    void refusesTwoBranchesWritingTheSameVariable() {
        ExecutionRecord finished = run("""
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel", "branches": ["left", "right"], "join": "done"},
                    {"id": "left",  "type": "slow", "millis": 1, "output": "same", "next": "done"},
                    {"id": "right", "type": "slow", "millis": 1, "output": "same", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """);

        assertEquals(ExecutionStatus.FAILED, finished.status());
        assertEquals("parallel.conflicting_writes", failureCode(finished));
    }

    /**
     * The most dangerous detail in this design.
     *
     * <p>A crash mid-parallel makes recovery re-run the whole step, branches
     * included. If the step did not ask its branches whether they may be repeated,
     * a parallel containing a payment would quietly take it twice.
     */
    @Test
    void refusesRecoveryWhenAnyBranchHoldsSomethingThatMayNotBeRepeated() {
        assertFalse(repeatableFor(twoBranches(", \"dangerous\": true")),
                "one unrepeatable step anywhere in any branch is enough");
    }

    @Test
    void allowsRecoveryWhenEveryBranchMayBeRepeated() {
        assertTrue(repeatableFor(twoBranches("")));
    }

    @Test
    void looksPastTheFirstStepOfABranch() {
        assertFalse(repeatableFor("""
                {
                  "id": "fan_out", "version": "1.0", "entry": "fan",
                  "steps": [
                    {"id": "fan", "type": "parallel", "branches": ["left", "right"], "join": "done"},
                    {"id": "left",  "type": "slow", "millis": 1, "output": "l", "next": "left2"},
                    {"id": "left2", "type": "slow", "millis": 1, "output": "l2", "next": "done",
                     "dangerous": true},
                    {"id": "right", "type": "slow", "millis": 1, "output": "r", "next": "done"},
                    {"id": "done", "type": "terminal", "status": "COMPLETED"}
                  ]
                }
                """), "the risk can be two steps deep");
    }

    /** Asks the parallel step whether recovery may re-run it, without running it. */
    private boolean repeatableFor(String workflow) {
        AtomicReference<InMemoryWorkflowRegistry> registry = new AtomicReference<>();

        ParallelStepExecutor parallel =
                new ParallelStepExecutor(lazily(registry), executors::get);

        StepExecutors all = StepExecutors.of(
                new SlowStep(), parallel,
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());
        executors.set(all);
        registry.set(new InMemoryWorkflowRegistry(new WorkflowCompiler(all)));

        var graph = registry.get().register(new WorkflowDefinitionReader().read(workflow));
        Step fan = graph.stepAt(StepId.of("fan"));

        return parallel.repeatable(fan, new ExecutionContext(
                ExecutionId.generate(), null, WorkflowId.of("fan_out"),
                graph.version(), StepId.of("fan"), JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void recordsHowManyBranchesRanAndHowFarEachWent() {
        ExecutionRecord finished = run(twoBranches(""));

        JsonNode attributes = stateStore.historyOf(finished.executionId()).get(0).attributes();

        assertEquals(2, attributes.path("parallel.branches").asInt());
        assertEquals(2, attributes.path("parallel.detail").size());
    }
}
