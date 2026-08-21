package io.pipemesh.console.demo;

import io.pipemesh.console.runtime.ConsolePrincipalResolver;
import io.pipemesh.console.runtime.QuotaInterceptor;
import io.pipemesh.core.dispatch.ExecutionDispatcher;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.StartMode;
import io.pipemesh.postgres.PostgresExecutionLeases;
import java.time.Duration;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;
import io.pipemesh.grpc.ExecutionUpdateBroker;
import io.pipemesh.grpc.PipeMeshServer;
import io.pipemesh.postgres.PostgresStateStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;

/**
 * A real runtime, on a real port, for the console to be an ordinary client of.
 *
 * <p>Not a stand-in: the point of the demo slice is that nothing about the path
 * is special, and a mocked runtime would prove the opposite. The console's own
 * resolver and quota interceptor are wired in, so a demo run is authenticated
 * and metered exactly as production would.
 */
@TestConfiguration
public class DemoRuntime {

    static final String WORKFLOW = """
            {
              "id": "demo", "version": "1.0", "entry": "check",
              "steps": [
                {"id": "check", "type": "condition", "expression": "$.input.mood == 'good'",
                 "onTrue": "celebrate", "onFalse": "console"},
                {"id": "celebrate", "type": "terminal", "status": "COMPLETED"},
                {"id": "console", "type": "terminal", "status": "COMPLETED"}
              ]
            }
            """;

    @Bean
    public PipeMeshServer runtimeServer(
            DataSource dataSource,
            ConsolePrincipalResolver principals,
            QuotaInterceptor quota) throws IOException {

        StepExecutors executors = StepExecutors.of(
                new ConditionStepExecutor(), new TerminalStepExecutor());

        InMemoryWorkflowRegistry workflows =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));
        workflows.register(new WorkflowDefinitionReader().read(WORKFLOW));

        PostgresStateStore stateStore = new PostgresStateStore(dataSource);
        ExecutionUpdateBroker broker = new ExecutionUpdateBroker();
        WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors, broker);

        // Dispatched rather than inline, which is what makes the demo live: start
        // returns a CREATED execution, the screen opens its watch, and only then
        // does a dispatcher drive it. Run inline, a fast workflow would be over
        // before anybody could watch it, and the demo would show a finished
        // execution rather than one happening (§28).
        ExecutionDispatcher dispatcher = new ExecutionDispatcher(
                workflows, stateStore, executor,
                new PostgresExecutionLeases(dataSource), "console-demo");
        // Two seconds, not milliseconds: the demo starts an execution and then
        // opens its watch, so a driver that pounces immediately can finish the
        // work before anybody is listening. That is a real property — the screen
        // then shows a finished execution instead of a running one, and since
        // WatchExecution closes cleanly on a terminal execution it is no longer a
        // hang. But it makes a test of live progress a coin flip, and the
        // interval is the honest knob: in production it is how often a driver
        // looks for work, and it is never a hundred milliseconds.
        new RecoveryScheduler(dispatcher::dispatchOnce, Duration.ofSeconds(2), failure -> {
        }).start();

        return new PipeMeshServer(
                new DefaultWorkflowRuntime(
                        workflows, stateStore, executor, null, StartMode.DISPATCHED),
                broker, 0, null, null, principals, List.of(), List.of(quota)).start();
    }

    /** Pointed at whatever port the server actually bound. */
    @Bean
    public RuntimeClient runtimeClient(PipeMeshServer server) {
        return new RuntimeClient("localhost:" + server.port());
    }
}
