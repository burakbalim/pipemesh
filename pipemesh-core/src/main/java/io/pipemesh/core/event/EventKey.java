package io.pipemesh.core.event;

import io.pipemesh.core.execution.OrganizationId;

import java.util.Objects;

/**
 * What a waiting execution listens for, and what an arriving event is matched
 * against.
 *
 * <p>The correlation value is the part that makes this work at all. A payment
 * service knows an order number, not which execution is waiting on it — so the
 * wait is filed under something both sides already know.
 *
 * <p>The organization is part of the key, not a filter applied afterwards. One
 * tenant publishing an event must not move another's execution along, and an
 * isolation boundary with an exception for events is not one (§22.2).
 */
public record EventKey(OrganizationId organization, String name, String correlation) {

    public EventKey {
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(name, "event name");
        Objects.requireNonNull(correlation, "correlation value");
        if (name.isBlank()) {
            throw new IllegalArgumentException("an event must have a name");
        }
        if (correlation.isBlank()) {
            throw new IllegalArgumentException(
                    "an event must have something to match on, or nothing could ever find it");
        }
    }

    @Override
    public String toString() {
        return organization + "/" + name + "#" + correlation;
    }
}
