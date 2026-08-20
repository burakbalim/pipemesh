package io.pipemesh.core.intent;

/**
 * Reads a message and names the workflow that should handle it (§19).
 *
 * <p>It returns a workflow and nothing else. It does not say where in the
 * workflow to start, what to skip, or how to proceed — a resolver that answered
 * those questions would have taken over the engine's job, which is the failure
 * §37 exists to prevent.
 */
public interface IntentResolver {

    /**
     * @throws IntentUnresolvedException when nothing settles it — saying no is an
     *                                   answer, and a better one than running the
     *                                   nearest workflow
     */
    ResolvedIntent resolve(String message);
}
