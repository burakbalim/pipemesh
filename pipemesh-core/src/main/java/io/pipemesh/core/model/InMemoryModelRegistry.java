package io.pipemesh.core.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a workflow's model alias to a provider.
 *
 * <p>Two aliases may point at the same provider, and an alias may be repointed
 * without touching a workflow — which is the whole reason a workflow says
 * {@code "model": "fast"} instead of naming a vendor (§12).
 */
public final class InMemoryModelRegistry implements ModelRegistry {

    private final Map<ModelId, MessagingProvider> providers = new ConcurrentHashMap<>();

    public InMemoryModelRegistry register(ModelId alias, MessagingProvider provider) {
        providers.put(
                Objects.requireNonNull(alias, "alias"),
                Objects.requireNonNull(provider, "provider"));
        return this;
    }

    @Override
    public Optional<MessagingProvider> providerFor(ModelId model) {
        return Optional.ofNullable(providers.get(model));
    }
}
