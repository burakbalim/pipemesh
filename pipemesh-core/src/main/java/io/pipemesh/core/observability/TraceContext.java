package io.pipemesh.core.observability;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * A W3C {@code traceparent}, kept as a value so it can be persisted with the
 * execution and handed back when the execution resumes.
 *
 * <p>This is the piece that makes a durable workflow observable. An execution
 * that waits three days for an approval and then finishes in another process
 * must appear as one trace, not two unrelated ones — so the trace id is written
 * down with the state at suspension and read back at resume (§22).
 *
 * <p>Deliberately a plain string format rather than a vendor's type: the runtime
 * should not depend on a tracing library to remember a trace id.
 */
public record TraceContext(String traceId, String spanId, boolean sampled) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VERSION = "00";

    public TraceContext {
        Objects.requireNonNull(traceId, "trace id");
        Objects.requireNonNull(spanId, "span id");
    }

    public static TraceContext generate() {
        return new TraceContext(randomHex(32), randomHex(16), true);
    }

    /** Parses a {@code traceparent} header, ignoring anything malformed. */
    public static Optional<TraceContext> parse(String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return Optional.empty();
        }
        String[] parts = traceParent.trim().split("-");
        if (parts.length < 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return Optional.empty();
        }
        return Optional.of(new TraceContext(parts[1], parts[2], !"00".equals(parts[3])));
    }

    /** A child context: same trace, new span. */
    public TraceContext child() {
        return new TraceContext(traceId, randomHex(16), sampled);
    }

    public String toTraceParent() {
        return VERSION + "-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder(length);
        while (hex.length() < length) {
            hex.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return hex.toString();
    }
}
