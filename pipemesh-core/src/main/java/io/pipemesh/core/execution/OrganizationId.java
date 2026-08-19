package io.pipemesh.core.execution;

import java.util.Objects;

/**
 * Who an execution belongs to.
 *
 * <p>Carried from the first write rather than added later: an execution's owner
 * decides which rows a query may return and which series a metric lands in, and
 * retrofitting that means migrating every row and re-labelling every dashboard.
 *
 * <p>{@link #DEFAULT} exists so a single-tenant deployment never has to think
 * about it.
 */
public record OrganizationId(String value) {

    public static final OrganizationId DEFAULT = new OrganizationId("default");

    public OrganizationId {
        Objects.requireNonNull(value, "organization id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("organization id must not be blank");
        }
    }

    public static OrganizationId of(String value) {
        return new OrganizationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
