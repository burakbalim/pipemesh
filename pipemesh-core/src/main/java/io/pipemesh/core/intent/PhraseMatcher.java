package io.pipemesh.core.intent;

import java.util.Locale;
import java.util.Optional;

/**
 * Settles a message against registered phrases, without asking anything.
 *
 * <p>Case-insensitive and bounded by word edges. Not a regular expression: a
 * pattern language inside a configuration file is a second language nobody chose,
 * and the same objection that keeps code out of a workflow keeps it out of here
 * (§23.1).
 *
 * <p>Word boundaries matter more than they look. Without them "iade" matches
 * "iadesiz" — a message saying a refund is <em>not</em> wanted would start the
 * refund workflow.
 */
final class PhraseMatcher {

    private PhraseMatcher() {
    }

    static Optional<IntentDefinition> match(String message, IntentRegistry registry) {
        String haystack = normalise(message);
        return registry.intents().stream()
                .filter(intent -> intent.matches().stream()
                        .anyMatch(phrase -> contains(haystack, normalise(phrase))))
                .findFirst();
    }

    private static String normalise(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim() + " ";
    }

    private static boolean contains(String haystack, String phrase) {
        return !phrase.isBlank() && haystack.contains(phrase);
    }
}
