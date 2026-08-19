package io.pipemesh.core.model;

/**
 * The boundary between the runtime and any model vendor (§13).
 *
 * <p>Streaming is deliberately absent: it belongs at this boundary but arrives
 * with the streaming contract, not with the first slice (§30).
 *
 * <p>A call here is provider I/O and must be made outside any open transaction.
 */
public interface MessagingProvider {

    /** The provider name a model registration selects, e.g. {@code anthropic}. */
    String id();

    CompletionResponse complete(CompletionRequest request);
}
