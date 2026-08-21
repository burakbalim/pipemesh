package io.pipemesh.core.workflow;

import java.util.Comparator;
import java.util.Objects;

/**
 * Version of a workflow definition. Recorded on every execution so a run stays
 * reproducible after the definition changes (§24).
 *
 * <p>The format is deliberately narrow: dot-separated numeric segments, compared
 * as numbers. Anything else is refused here rather than at the moment someone
 * asks for "the newest version" — a version that cannot be ordered would mean
 * silently picking the wrong graph, and by then nobody is watching.
 *
 * <p>Ordering and identity are not the same thing: {@code 1.0} and {@code 1.0.0}
 * compare equal but are two different registrations. The registry keys on the
 * string; only the choice of "newest" uses the comparison.
 */
public record WorkflowVersion(String value) implements Comparable<WorkflowVersion> {

    private static final String SEPARATOR = "\\.";

    /** Trailing zeros do not change the value, so the shorter list is padded. */
    private static final Comparator<WorkflowVersion> BY_SEGMENT = (left, right) -> {
        int[] a = left.segments();
        int[] b = right.segments();
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int comparison = Integer.compare(
                    i < a.length ? a[i] : 0,
                    i < b.length ? b[i] : 0);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    };

    public WorkflowVersion {
        Objects.requireNonNull(value, "workflow version");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workflow version must not be blank");
        }
        parse(value);
    }

    public static WorkflowVersion of(String value) {
        return new WorkflowVersion(value);
    }

    /**
     * Newest first. Callers pick a version to start on; a running execution never
     * consults this — it resumes on the version already written to its record.
     */
    @Override
    public int compareTo(WorkflowVersion other) {
        return BY_SEGMENT.compare(this, other);
    }

    private int[] segments() {
        return parse(value);
    }

    private static int[] parse(String value) {
        String[] parts = value.split(SEPARATOR, -1);
        int[] segments = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            segments[i] = segment(value, parts[i]);
        }
        return segments;
    }

    private static int segment(String value, String part) {
        try {
            int segment = Integer.parseInt(part);
            if (segment < 0) {
                throw new NumberFormatException(part);
            }
            return segment;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "workflow version '" + value + "' is not a dot-separated list of numbers;"
                            + " an unorderable version cannot be resolved to 'the newest'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
