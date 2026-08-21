package io.pipemesh.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.pipemesh.core.cost.ModelPrice;
import io.pipemesh.core.model.ModelId;

import java.util.Objects;
import java.util.Optional;

/**
 * A model registration as written in {@code models.json} (§12).
 *
 * <p>{@code protocol} decides which factory builds the provider; everything else
 * is that protocol's business. A workflow references only {@link #alias()}.
 */
public record ModelDefinition(ModelId alias, String protocol, JsonNode settings) {

    public ModelDefinition {
        Objects.requireNonNull(alias, "model alias");
        Objects.requireNonNull(settings, "settings");
        if (protocol == null || protocol.isBlank()) {
            throw new ConfigException("model '" + alias + "' does not declare a protocol");
        }
        settings = settings.deepCopy();
    }

    public String required(String field) {
        String value = settings.path(field).asText("");
        if (value.isBlank()) {
            throw new ConfigException("model '" + alias + "' is missing '" + field + "'");
        }
        return value;
    }

    public String optional(String field, String fallback) {
        String value = settings.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    /**
     * What this model charges, if the registration says (§39).
     *
     * <p>Absent is not free. A local model really does cost nothing and a
     * forgotten price looks the same from here, so the two are kept apart —
     * see {@code ModelPrices}.
     */
    public Optional<ModelPrice> price() {
        String input = settings.path("inputPricePerMillion").asText("");
        String output = settings.path("outputPricePerMillion").asText("");
        if (input.isBlank() && output.isBlank()) {
            return Optional.empty();
        }
        if (input.isBlank() || output.isBlank()) {
            throw new ConfigException(
                    "model '" + alias + "' prices only one direction; both input and output"
                            + " prices are needed, or neither");
        }
        return Optional.of(ModelPrice.of(input, output));
    }

    /**
     * Reads a credential from the environment variable the config names.
     *
     * <p>Config files are committed and shared; secrets are not. A definition says
     * which variable holds the key, never the key itself.
     */
    public String secretFromEnvironment(String field) {
        String variable = settings.path(field).asText("");
        return variable.isBlank() ? null : System.getenv(variable);
    }
}
