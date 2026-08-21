package io.pipemesh.core.capability;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Who an execution is running on behalf of, and what they are allowed to reach.
 *
 * <p><b>A caller does not get to say this about itself.</b> A request arriving
 * over the wire with its own list of permissions is a lock hung next to the door;
 * whoever accepts the request resolves the identity — a token, a header, a
 * certificate — and hands the runtime the result.
 *
 * <p>The runtime only checks what it was told. It cannot authenticate anyone, and
 * a library that pretended otherwise would be worse than one that is clear about
 * where its boundary is (§23).
 *
 * <p>{@code unrestricted} is a field rather than a magic permission string,
 * because "holds everything" is a different statement from "holds this list", and
 * a wildcard hiding inside a set is the kind of thing that gets copied into a
 * config file by accident.
 */
public record Principal(String id, Set<String> permissions, boolean unrestricted) {

    /**
     * The caller that already holds the objects.
     *
     * <p>Code in the same process built the runtime, the registries and the
     * workflows. There is nothing to withhold from it, and pretending otherwise
     * would only mean writing permissions that no boundary enforces.
     */
    public static final Principal SYSTEM = new Principal("system", Set.of(), true);

    /** A caller nobody identified: no permissions, and no pretending otherwise. */
    public static final Principal ANONYMOUS = new Principal("anonymous", Set.of(), false);

    public Principal {
        Objects.requireNonNull(id, "principal id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("principal id must not be blank");
        }
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
    }

    public Principal(String id, Set<String> permissions) {
        this(id, permissions, false);
    }

    public static Principal of(String id, String... permissions) {
        return new Principal(id, Set.of(permissions), false);
    }

    public boolean holds(String permission) {
        return unrestricted || permissions.contains(permission);
    }

    /** The permissions {@code required} asks for that this principal does not have. */
    public List<String> missingFrom(List<String> required) {
        return required.stream().filter(permission -> !holds(permission)).toList();
    }

    @Override
    public String toString() {
        return id;
    }
}
