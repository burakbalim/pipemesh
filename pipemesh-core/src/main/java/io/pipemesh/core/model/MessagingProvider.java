package io.pipemesh.core.model;

import java.util.function.Consumer;

/**
 * The boundary between the runtime and any model vendor (§13).
 *
 * <p>A call here is provider I/O and must be made outside any open transaction.
 */
public interface MessagingProvider {

    /** The provider name a model registration selects, e.g. {@code anthropic}. */
    String id();

    CompletionResponse complete(CompletionRequest request);

    /**
     * The same call, with pieces handed over as they arrive (§30).
     *
     * <p>A callback rather than a {@code Stream}: a lazily consumed stream ties an
     * open connection to whenever the caller gets round to draining it, and makes
     * a step's synchronous contract someone else's problem. This way the
     * connection's lifetime stays inside the provider and the engine's step model
     * does not change.
     *
     * <p>The default degrades to a single chunk, so a provider that cannot stream
     * still works everywhere streaming is asked for — one large piece instead of
     * many small ones.
     */
    default CompletionResponse stream(CompletionRequest request, Consumer<CompletionChunk> onChunk) {
        CompletionResponse response = complete(request);
        onChunk.accept(new CompletionChunk(response.content().asText(""), 0));
        return response;
    }
}
