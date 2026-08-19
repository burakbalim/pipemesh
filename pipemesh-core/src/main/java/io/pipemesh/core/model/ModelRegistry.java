package io.pipemesh.core.model;

import java.util.Optional;

/**
 * Resolves a workflow's model alias to the provider that serves it. A workflow
 * never names a provider, and never sees a credential (§12).
 */
public interface ModelRegistry {

    Optional<MessagingProvider> providerFor(ModelId model);
}
