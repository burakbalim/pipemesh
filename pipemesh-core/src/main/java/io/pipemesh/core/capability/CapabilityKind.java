package io.pipemesh.core.capability;

/**
 * Who owns the implementation behind a capability.
 *
 * <p>Registration metadata, never visible to a workflow. It exists so operators
 * can tell business code from an external tool without the DSL having to (§9.8).
 */
public enum CapabilityKind {

    /** Business code owned and deployed by the calling application. */
    APPLICATION,

    /** A tool or service the runtime reaches out to. */
    EXTERNAL
}
