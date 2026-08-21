package io.pipemesh.core.config;

import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.execution.StepExecutors;
import io.pipemesh.core.execution.step.ApprovalStepExecutor;
import io.pipemesh.core.execution.step.CapabilityStepExecutor;
import io.pipemesh.core.execution.step.ConditionStepExecutor;
import io.pipemesh.core.execution.step.LlmStepExecutor;
import io.pipemesh.core.execution.step.TerminalStepExecutor;
import io.pipemesh.core.model.CompletionRequest;
import io.pipemesh.core.model.CompletionResponse;
import io.pipemesh.core.model.MessagingProvider;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.schema.WorkflowValidator;
import io.pipemesh.core.state.memory.InMemoryApprovalStore;
import io.pipemesh.core.workflow.InMemoryWorkflowRegistry;
import io.pipemesh.core.workflow.WorkflowCompiler;
import io.pipemesh.core.workflow.WorkflowDefinition;
import io.pipemesh.core.workflow.WorkflowId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the example configuration that ships with the project.
 *
 * <p>This is the project's own success criterion under test: everything the
 * runtime needs comes out of a directory, and the second workflow in it needed no
 * code to exist (§46).
 */
class ConfigRepositoryTest {

    /** Stands in for a real endpoint; what is under test is the wiring, not an answer. */
    private record StubProviderFactory(String protocol) implements ModelProviderFactory {

        @Override
        public MessagingProvider create(ModelDefinition definition) {
            String model = definition.required("model");
            return new MessagingProvider() {

                @Override
                public String id() {
                    return definition.alias().value() + ":" + model;
                }

                @Override
                public CompletionResponse complete(CompletionRequest request) {
                    throw new UnsupportedOperationException("not called in this test");
                }
            };
        }
    }

    private ConfigRepository config;

    @BeforeEach
    void openExampleRepository() {
        Path root = Path.of("..", "examples", "approval-flow");
        assertTrue(Files.isDirectory(root),
                "the example config repository should ship with the project: " + root.toAbsolutePath());
        config = new ConfigRepository(root);
    }

    @Test
    void readsEveryWorkflowInTheRepository() {
        List<String> ids = config.workflows().stream()
                .map(WorkflowDefinition::id)
                .map(WorkflowId::value)
                .toList();

        assertEquals(List.of("refund_request", "venue_booking"), ids);
    }

    @Test
    void compilesEveryWorkflowWithoutACodeChange() {
        StepExecutors executors = StepExecutors.of(
                new LlmStepExecutor(
                        config.modelRegistry(List.of(new StubProviderFactory("openai-compatible"))),
                        config.promptRegistry(),
                        config.schemaRegistry()),
                new ConditionStepExecutor(),
                new CapabilityStepExecutor(config.capabilityRegistry(), List.of()),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor());

        InMemoryWorkflowRegistry registry =
                new InMemoryWorkflowRegistry(new WorkflowCompiler(executors));

        config.workflows().forEach(registry::register);

        assertTrue(registry.latest(WorkflowId.of("venue_booking")).isPresent());
        assertTrue(registry.latest(WorkflowId.of("refund_request")).isPresent());
    }

    @Test
    void readsModelAliasesAndTheProtocolBehindThem() {
        List<ModelDefinition> models = config.models();

        assertEquals(2, models.size());
        assertTrue(models.stream().allMatch(model -> model.protocol().equals("openai-compatible")));
    }

    @Test
    void buildsProvidersForEveryDeclaredAlias() {
        var registry = config.modelRegistry(List.of(new StubProviderFactory("openai-compatible")));

        assertTrue(registry.providerFor(ModelId.of("fast")).isPresent());
        assertTrue(registry.providerFor(ModelId.of("reasoning")).isPresent());
    }

    @Test
    void refusesAModelWhoseProtocolNobodyCanBuild() {
        ConfigException failure = assertThrows(ConfigException.class,
                () -> config.modelRegistry(List.of(new StubProviderFactory("telepathy"))));

        assertTrue(failure.getMessage().contains("openai-compatible"));
    }

    @Test
    void readsCapabilityRegistrationsWithTheirOwnership() {
        CapabilityDescriptor venueSearch = config.capabilityRegistry()
                .find(CapabilityId.of("venue_search")).orElseThrow();

        assertEquals(CapabilityKind.EXTERNAL, venueSearch.kind());
        assertEquals("platform-team", venueSearch.owner());
        assertEquals(List.of("places.read"), venueSearch.permissions());
        assertEquals("mcp", venueSearch.executionType());
    }

    @Test
    void readsAnApplicationOwnedCapabilityTheSameWay() {
        CapabilityDescriptor refund = config.capabilityRegistry()
                .find(CapabilityId.of("refund_payment")).orElseThrow();

        assertEquals(CapabilityKind.APPLICATION, refund.kind());
        assertEquals("grpc", refund.executionType());
    }

    @Test
    void namesPromptsAfterTheirPathAndVersion() {
        var prompts = config.promptRegistry();

        assertTrue(prompts.find(PromptId.of("venue_booking.extraction.v1")).isPresent());
        assertTrue(prompts.find(PromptId.of("venue_booking.refund.v1")).isPresent());
    }

    @Test
    void readsSchemasByFileName() {
        assertTrue(config.schemaRegistry().find("venue-request").isPresent());
    }

    @Test
    void wiresTheExampleWorkflowToItsSchemaById() {
        var schema = config.schemaRegistry().find("venue-request").orElseThrow();

        assertEquals("object", schema.path("type").asText());
        assertTrue(config.workflows().stream()
                .flatMap(workflow -> workflow.steps().stream())
                .anyMatch(step -> step.config().path("outputSchema").asText().equals("venue-request")),
                "the example should name its schema rather than inline it");
    }

    @Test
    void readsTheIntentsAMessageCanBeReadAs() {
        var intents = config.intents();

        assertEquals(2, intents.intents().size());
        assertTrue(intents.canAskAModel(), "the example configures a model for the hard cases");
        assertEquals(0.6, intents.minimumConfidence(), 0.0001);
    }

    @Test
    void everyIntentNamesAWorkflowThatExists() {
        var workflows = config.workflows().stream()
                .map(workflow -> workflow.id().value())
                .toList();

        assertTrue(config.intents().intents().stream()
                        .allMatch(intent -> workflows.contains(intent.workflow().value())),
                "an intent pointing at a workflow nobody registered would fail only when someone said the words");
    }

    @Test
    void everyExampleWorkflowIsAShapeTheFormatAllows() {
        WorkflowValidator shape = new WorkflowValidator(StepExecutors.of(
                new LlmStepExecutor(config.modelRegistry(
                        List.of(new StubProviderFactory("openai-compatible"))), config.promptRegistry()),
                new ConditionStepExecutor(),
                new CapabilityStepExecutor(config.capabilityRegistry(), List.of()),
                new ApprovalStepExecutor(new InMemoryApprovalStore()),
                new TerminalStepExecutor()));

        config.workflowSources().forEach(shape::validate);
    }

    @Test
    void rejectsADirectoryThatIsNotThere() {
        assertThrows(ConfigException.class, () -> new ConfigRepository(Path.of("no", "such", "place")));
    }
}
