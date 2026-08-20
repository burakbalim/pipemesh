package io.pipemesh.core.policy;

import java.time.Duration;

/**
 * Reads the durations a config file writes: {@code 500ms}, {@code 30s},
 * {@code 5m}.
 *
 * <p>ISO-8601 ({@code PT30S}) is what {@link Duration} parses natively and what
 * nobody wants to type into a workflow.
 */
public final class DurationText {

    private DurationText() {
    }

    public static Duration parse(String text, Duration fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String trimmed = text.trim().toLowerCase();
        try {
            if (trimmed.endsWith("ms")) {
                return Duration.ofMillis(number(trimmed, 2));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(number(trimmed, 1));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(number(trimmed, 1));
            }
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(number(trimmed, 1));
            }
            return Duration.ofMillis(Long.parseLong(trimmed));
        } catch (NumberFormatException notADuration) {
            throw new IllegalArgumentException("not a duration: '" + text + "'");
        }
    }

    private static long number(String text, int suffixLength) {
        return Long.parseLong(text.substring(0, text.length() - suffixLength).trim());
    }
}
