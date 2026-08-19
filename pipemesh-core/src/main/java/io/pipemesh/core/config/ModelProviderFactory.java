package io.pipemesh.core.config;

import io.pipemesh.core.model.MessagingProvider;

/**
 * Builds a provider for one wire protocol.
 *
 * <p>The loader knows how to read {@code models.json}; it does not know what an
 * {@code openai-compatible} endpoint needs. Keeping that here means a new
 * protocol arrives as a new factory, without core learning anything about it.
 */
public interface ModelProviderFactory {

    /** The {@code protocol} value in a model definition, e.g. {@code openai-compatible}. */
    String protocol();

    MessagingProvider create(ModelDefinition definition);
}
