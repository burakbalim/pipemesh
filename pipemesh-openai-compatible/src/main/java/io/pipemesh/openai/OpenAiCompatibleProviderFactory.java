package io.pipemesh.openai;

import io.pipemesh.core.config.ModelDefinition;
import io.pipemesh.core.config.ModelProviderFactory;
import io.pipemesh.core.model.MessagingProvider;

import java.time.Duration;

/**
 * Builds an {@link OpenAiCompatibleProvider} from a model definition:
 *
 * <pre>
 * "fast": {
 *   "protocol": "openai-compatible",
 *   "baseUrl": "http://localhost:11434/v1",
 *   "model": "llama3.2",
 *   "apiKeyEnv": "OPENAI_API_KEY",
 *   "timeoutSeconds": 60
 * }
 * </pre>
 *
 * <p>The credential is named, not written: config files get committed, keys
 * should not.
 */
public final class OpenAiCompatibleProviderFactory implements ModelProviderFactory {

    public static final String PROTOCOL = "openai-compatible";

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public MessagingProvider create(ModelDefinition definition) {
        long timeoutSeconds = definition.settings().path("timeoutSeconds").asLong(60);

        return new OpenAiCompatibleProvider(new OpenAiCompatibleConfig(
                definition.alias().value(),
                definition.required("baseUrl"),
                definition.required("model"),
                definition.secretFromEnvironment("apiKeyEnv"),
                Duration.ofSeconds(timeoutSeconds)));
    }
}
