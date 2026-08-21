package io.pipemesh.runtime;

import io.pipemesh.core.capability.CapabilityInvoker;
import io.pipemesh.core.capability.CapabilityProvider;
import io.pipemesh.core.capability.CapabilityRegistry;
import io.pipemesh.core.config.ConfigRepository;
import io.pipemesh.core.dispatch.ExecutionDispatcher;
import io.pipemesh.core.dispatch.ExecutionLeases;
import io.pipemesh.core.execution.DefaultWorkflowRuntime;
import io.pipemesh.core.execution.RecoveryScheduler;
import io.pipemesh.core.execution.RecoverySweeper;
import io.pipemesh.core.execution.StartMode;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.intent.DefaultIntentResolver;
import io.pipemesh.core.intent.IntentResolver;
import io.pipemesh.core.execution.WorkflowExecutor;
import io.pipemesh.core.execution.step.AgentStepExecutor;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.ParallelStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.execution.step.TransformStepExecutor;
import io.pipemesh.core.execution.step.WaitStepExecutor;
import io.pipemesh.core.model.ModelRegistry;
import io.pipemesh.core.prompt.PromptRegistry;
import io.pipemesh.core.schema.SchemaRegistry;
import io.pipemesh.core.schema.WorkflowValidator;
import io.pipemesh.core.state.ApprovalStore;
import io.pipemesh.core.state.StateStore;
import io.pipemesh.core.event.WaitStore;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.state.memory.InMemoryExecutionLeases;
import io.pipemesh.core.state.memory.InMemoryStateStore;
import io.pipemesh.core.state.memory.InMemoryWaitStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.grpc.ExecutionUpdateBroker;
import io.pipemesh.grpc.PipeMeshServer;
import io.pipemesh.grpc.WorkerCapabilityProvider;
import io.pipemesh.grpc.WorkerRegistry;
import io.pipemesh.openai.OpenAiCompatibleProviderFactory;
import io.pipemesh.postgres.PostgresApprovalStore;
import io.pipemesh.postgres.PostgresExecutionLeases;
import io.pipemesh.postgres.PostgresStateStore;
import io.pipemesh.postgres.PostgresWaitStore;
import io.pipemesh.postgres.SchemaMigrator;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a configuration directory and an environment into a running server.
 *
 * <p>What is code and what is configuration is the line this class draws. Which
 * step types exist is code — they are the language a workflow is written in.
 * Which workflows, models, capabilities and prompts exist is configuration, read
 * from a directory (§31), so adding one is dropping in a file rather than
 * changing this class. If that ever stops being true, §46's success criterion has
 * been lost.
 *
 * <p>What this does <em>not</em> decide is who is calling, what plan they are on,
 * or whether they have any quota left. Those belong to a deployment that
 * identifies people, and are composed in from outside rather than branched on
 * here.
 */
public final class RuntimeAssembly implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAssembly.class);

    private final PipeMeshServer server;
    private final ExecutionUpdateBroker broker;
    private final PostgresUpdateChannel channel;
    private final RecoveryScheduler recovery;
    private final ExecutionDispatcher dispatcher;
    private final RecoveryScheduler dispatching;

    public static RuntimeAssembly of(RuntimeSettings settings) throws IOException {
        return new RuntimeAssembly(settings);
    }

    private RuntimeAssembly(RuntimeSettings settings) throws IOException {
        DataSource dataSource = dataSourceOf(settings);
        announce(settings);

        // Migrated here rather than by whoever assembles: a runtime that only
        // has its tables when somebody remembered to ask is a runtime that will
        // one day start without them. Idempotent and lock-protected, so calling
        // it again — from a job, from another replica — costs nothing.
        migrate(settings);

        StateStore stateStore = dataSource == null
                ? new InMemoryStateStore() : new PostgresStateStore(dataSource);
        ApprovalStore approvals = dataSource == null
                ? new InMemoryApprovalStore() : new PostgresApprovalStore(dataSource);
        WaitStore waits = dataSource == null
                ? new InMemoryWaitStore() : new PostgresWaitStore(dataSource);

        ConfigRepository config = new ConfigRepository(settings.configDirectory());
        ModelRegistry models = config.modelRegistry(List.of(new OpenAiCompatibleProviderFactory()));
        PromptRegistry prompts = config.promptRegistry();
        SchemaRegistry schemas = config.schemaRegistry();
        CapabilityRegistry capabilities = config.capabilityRegistry();

        WorkerRegistry workers = new WorkerRegistry();

        // With a database, updates cross processes; without one there is only
        // this process, and a channel would have nothing to carry them over.
        this.channel = dataSource == null ? null : new PostgresUpdateChannel(dataSource);
        ExecutionUpdateBroker broker = channel == null
                ? new ExecutionUpdateBroker() : new ExecutionUpdateBroker(channel);
        this.broker = broker;

        // Tied with a reference rather than an order nobody can follow: a parallel
        // step runs other steps, so it needs the set it is part of.
        AtomicReference<StepExecutors> all = new AtomicReference<>();
        AtomicReference<InMemoryWorkflowRegistry> registry = new AtomicReference<>();

        List<CapabilityProvider> providers = List.of(new WorkerCapabilityProvider(workers));
        CapabilityInvoker invoker = new CapabilityInvoker(capabilities, providers);

        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(models, prompts, schemas, broker),
                new ConditionStepExecutor(),
                new CapabilityStepExecutor(invoker),
                new ApprovalStepExecutor(approvals),
                new WaitStepExecutor(waits),
                new TransformStepExecutor(),
                new AgentStepExecutor(models, prompts, invoker),
                new ParallelStepExecutor(lazily(registry), all::get),
                new TerminalStepExecutor());
        all.set(executors);

        InMemoryWorkflowRegistry workflows = new InMemoryWorkflowRegistry(
                new WorkflowCompiler(executors), new WorkflowValidator(executors));
        registry.set(workflows);
        config.workflowSources().forEach(workflows::register);

        WorkflowExecutor executor = new WorkflowExecutor(stateStore, executors, broker);

        this.recovery = new RecoveryScheduler(
                new RecoverySweeper(workflows, stateStore, executor, executors)::sweep,
                settings.recoveryInterval(), failure -> log.warn("Recovery pass failed", failure));

        ExecutionLeases leases = dataSource == null
                ? new InMemoryExecutionLeases((InMemoryStateStore) stateStore)
                : new PostgresExecutionLeases(dataSource);

        this.dispatcher = settings.dispatching()
                ? new ExecutionDispatcher(workflows, stateStore, executor, leases, ownerName())
                : null;
        this.dispatching = dispatcher == null ? null : new RecoveryScheduler(
                dispatcher::dispatchOnce, settings.dispatchInterval(),
                failure -> log.warn("Dispatch pass failed", failure));

        // Intents are configuration too (§31): a deployment that reads workflows
        // from a directory has no reason to make "what can a message mean" the one
        // thing that needs a code change.
        IntentResolver intents = new DefaultIntentResolver(config.intents(), models, prompts);

        this.server = new PipeMeshServer(
                new DefaultWorkflowRuntime(
                        workflows, stateStore, executor, intents,
                        settings.startsInline() ? StartMode.INLINE : StartMode.DISPATCHED),
                broker, settings.port(), null, workers);
    }

    public RuntimeAssembly start() throws IOException {
        server.start();
        recovery.start();
        if (dispatching != null) {
            dispatching.start();
        }
        log.info("PipeMesh runtime listening on {}", server.port());
        return this;
    }

    public int port() {
        return server.port();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    /**
     * Applies the schema this runtime needs.
     *
     * <p>Called on assembly, and public so a deployment can run it as its own
     * step instead ({@code --migrate-only}). Serialised with an advisory lock, so
     * several replicas starting together do not race each other into the same
     * CREATE TABLE — nothing about a single node needs that; every deploy of more
     * than one does.
     */
    public static void migrate(RuntimeSettings settings) {
        DataSource dataSource = dataSourceOf(settings);
        if (dataSource == null) {
            log.warn("No {} configured, so there is no schema to apply", RuntimeSettings.DB_URL);
            return;
        }
        new SchemaMigrator(dataSource).migrate();
        log.info("Schema is up to date");
    }

    private void announce(RuntimeSettings settings) {
        if (!settings.durable()) {
            log.warn("No {} configured: execution state lives in memory and dies with this"
                    + " process. Restarts lose work, and nothing can be recovered.",
                    RuntimeSettings.DB_URL);
        }
        // Said plainly because it is true and easy to miss: a runtime that
        // authenticates nobody has no isolation to enforce (§22.2). For a
        // single-tenant install that is the right answer; for a shared one it is
        // not, and nobody should find out later.
        log.warn("No principal resolver is configured: every caller is anonymous and"
                + " organizations are not isolated from one another. This is expected for a"
                + " single-tenant deployment.");

        log.info("Dispatching is {}; start is {}", settings.dispatching() ? "on" : "off",
                settings.startsInline() ? "inline" : "dispatched");

        if (!settings.dispatching() && settings.startsInline()) {
            log.info("This process drives what it is asked to, on the caller's thread");
        } else if (!settings.dispatching()) {
            log.info("This process serves callers only: something else must drive executions"
                    + " ({}=off, {}=dispatched)", RuntimeSettings.DISPATCH, RuntimeSettings.START);
        }
    }

    private static DataSource dataSourceOf(RuntimeSettings settings) {
        if (!settings.durable()) {
            return null;
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(settings.databaseUrl());
        source.setUser(settings.databaseUser());
        source.setPassword(settings.databasePassword());
        return source;
    }

    /** Names this instance in a lease, so "who is running this" has an answer. */
    private static String ownerName() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "pipemesh-runtime" : host;
    }

    private static io.pipemesh.core.workflow.WorkflowRegistry lazily(
            AtomicReference<InMemoryWorkflowRegistry> registry) {

        return new io.pipemesh.core.workflow.WorkflowRegistry() {
            @Override
            public java.util.Optional<io.pipemesh.core.workflow.ExecutionGraph> find(
                    io.pipemesh.core.workflow.WorkflowId id,
                    io.pipemesh.core.workflow.WorkflowVersion version) {
                return registry.get().find(id, version);
            }

            @Override
            public java.util.Optional<io.pipemesh.core.workflow.ExecutionGraph> latest(
                    io.pipemesh.core.workflow.WorkflowId id) {
                return registry.get().latest(id);
            }
        };
    }

    @Override
    public void close() {
        if (dispatching != null) {
            dispatching.close();
        }
        if (dispatcher != null) {
            dispatcher.close();
        }
        recovery.close();
        server.close();
        broker.close();
        if (channel != null) {
            channel.close();
        }
    }
}
