package io.pipemesh.core.prompt;

import java.util.Optional;

/** Resolves a prompt reference to its text (§11). */
public interface PromptRegistry {

    Optional<PromptTemplate> find(PromptId id);
}
