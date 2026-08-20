package io.pipemesh.core.model;

import java.util.Objects;

/**
 * A piece of an answer as it arrives.
 *
 * <p>{@code index} counts from zero so a consumer can tell "nothing yet" from
 * "the first token was empty", and can reassemble out-of-order delivery if a
 * transport ever reorders.
 */
public record CompletionChunk(String text, int index) {

    public CompletionChunk {
        Objects.requireNonNull(text, "text");
    }
}
