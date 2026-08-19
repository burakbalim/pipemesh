package io.pipemesh.core.prompt;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds prompt templates in memory, keyed by their versioned id. */
public final class InMemoryPromptRegistry implements PromptRegistry {

    private final Map<PromptId, PromptTemplate> prompts = new ConcurrentHashMap<>();

    public PromptTemplate register(PromptId id, String text) {
        PromptTemplate template = new PromptTemplate(id, text);
        prompts.put(id, template);
        return template;
    }

    @Override
    public Optional<PromptTemplate> find(PromptId id) {
        return Optional.ofNullable(prompts.get(id));
    }
}
