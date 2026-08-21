package io.pipemesh.core.execution;

import io.pipemesh.core.capability.Principal;

/**
 * A caller reached for something belonging to another organization.
 *
 * <p>Refused rather than reported as missing. Hiding the existence of an id leaks
 * less, but execution ids are random and nobody finds one by guessing — and an
 * operator chasing a "not found" that is really a "not yours" spends the
 * afternoon looking in the wrong place (§22.2).
 */
public class OrganizationMismatchException extends RuntimeException {

    public OrganizationMismatchException(Principal caller, OrganizationId owner) {
        super("'" + caller + "' belongs to "
                + caller.organizationIfKnown().map(OrganizationId::value).orElse("no organization")
                + " and may not reach work owned by " + owner);
    }
}
