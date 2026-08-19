package io.pipemesh.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pipemesh.core.capability.CapabilityDescriptor;
import io.pipemesh.core.capability.CapabilityId;
import io.pipemesh.core.capability.CapabilityKind;
import io.pipemesh.core.capability.InMemoryCapabilityRegistry;
import io.pipemesh.core.model.InMemoryModelRegistry;
import io.pipemesh.core.model.ModelId;
import io.pipemesh.core.prompt.InMemoryPromptRegistry;
import io.pipemesh.core.prompt.PromptId;
import io.pipemesh.core.workflow.WorkflowDefinition;
import io.pipemesh.core.workflow.WorkflowDefinitionReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads a directory of workflows, models, capabilities and prompts (§31).
 *
 * <p>This is what makes the project's own success criterion checkable: adding a
 * workflow should be a matter of dropping files in here, and if it ever requires
 * a code change, the abstraction has leaked (§46).
 *
 * <pre>
 * approval-flow/
 * ├── workflows/     one JSON per workflow
 * ├── models/        models.json — aliases and the protocol behind each
 * ├── capabilities/  one JSON per capability registration
 * └── prompts/       group/name.version.md
 * </pre>
 */
public final class ConfigRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public ConfigRepository(Path root) {
        this.root = Objects.requireNonNull(root, "config root");
        if (!Files.isDirectory(root)) {
            throw new ConfigException("config repository is not a directory: " + root.toAbsolutePath());
        }
    }

    public List<WorkflowDefinition> workflows() {
        WorkflowDefinitionReader reader = new WorkflowDefinitionReader();
        return jsonFilesIn("workflows").stream()
                .map(file -> reader.read(read(file)))
                .toList();
    }

    /**
     * Model aliases and the protocol behind each. Turning these into providers
     * needs a {@link ModelProviderFactory} per protocol — the loader deliberately
     * stops at the description.
     */
    public List<ModelDefinition> models() {
        Path file = root.resolve("models").resolve("models.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<ModelDefinition> definitions = new ArrayList<>();
        parse(file).path("models").fields().forEachRemaining(entry -> definitions.add(
                new ModelDefinition(
                        ModelId.of(entry.getKey()),
                        entry.getValue().path("protocol").asText(""),
                        entry.getValue())));
        return List.copyOf(definitions);
    }

    public InMemoryModelRegistry modelRegistry(List<ModelProviderFactory> factories) {
        Map<String, ModelProviderFactory> byProtocol = factories.stream()
                .collect(Collectors.toUnmodifiableMap(ModelProviderFactory::protocol, factory -> factory));

        InMemoryModelRegistry registry = new InMemoryModelRegistry();
        for (ModelDefinition definition : models()) {
            ModelProviderFactory factory = byProtocol.get(definition.protocol());
            if (factory == null) {
                throw new ConfigException("model '" + definition.alias() + "' needs a '"
                        + definition.protocol() + "' provider factory, which was not supplied");
            }
            registry.register(definition.alias(), factory.create(definition));
        }
        return registry;
    }

    public InMemoryCapabilityRegistry capabilityRegistry() {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        for (Path file : jsonFilesIn("capabilities")) {
            registry.register(capability(parse(file), file));
        }
        return registry;
    }

    public InMemoryPromptRegistry promptRegistry() {
        InMemoryPromptRegistry registry = new InMemoryPromptRegistry();
        Path prompts = root.resolve("prompts");
        if (!Files.isDirectory(prompts)) {
            return registry;
        }
        try (Stream<Path> files = Files.walk(prompts)) {
            files.filter(file -> file.toString().endsWith(".md"))
                    .forEach(file -> registry.register(promptId(prompts, file), read(file)));
        } catch (IOException unreadable) {
            throw new ConfigException("could not read prompts under " + prompts, unreadable);
        }
        return registry;
    }

    /** {@code prompts/venue_booking/extraction.v1.md} becomes {@code venue_booking.extraction.v1}. */
    private PromptId promptId(Path prompts, Path file) {
        String relative = prompts.relativize(file).toString();
        String withoutExtension = relative.substring(0, relative.length() - ".md".length());
        return PromptId.of(withoutExtension.replace(File.separatorChar, '.'));
    }

    private CapabilityDescriptor capability(JsonNode json, Path file) {
        String id = json.path("id").asText("");
        if (id.isBlank()) {
            throw new ConfigException("capability in " + file.getFileName() + " has no id");
        }
        return new CapabilityDescriptor(
                CapabilityId.of(id),
                json.path("description").asText(""),
                kindOf(json, file),
                json.path("owner").asText(""),
                json.path("version").asText("1.0"),
                permissions(json),
                json.has("inputSchema") ? json.get("inputSchema") : null,
                json.has("outputSchema") ? json.get("outputSchema") : null,
                json.path("execution"));
    }

    private CapabilityKind kindOf(JsonNode json, Path file) {
        String kind = json.path("kind").asText("external");
        try {
            return CapabilityKind.valueOf(kind.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new ConfigException("capability in " + file.getFileName()
                    + " declares unknown kind '" + kind + "'");
        }
    }

    private List<String> permissions(JsonNode json) {
        List<String> permissions = new ArrayList<>();
        json.path("permissions").forEach(permission -> permissions.add(permission.asText()));
        return List.copyOf(permissions);
    }

    private List<Path> jsonFilesIn(String directory) {
        Path folder = root.resolve(directory);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        } catch (IOException unreadable) {
            throw new ConfigException("could not list " + folder, unreadable);
        }
    }

    private JsonNode parse(Path file) {
        try {
            return JSON.readTree(read(file));
        } catch (IOException malformed) {
            throw new ConfigException(file.getFileName() + " is not valid JSON", malformed);
        }
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new ConfigException("could not read " + file, unreadable);
        }
    }
}
