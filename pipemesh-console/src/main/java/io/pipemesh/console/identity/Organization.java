package io.pipemesh.console.identity;

import java.time.Instant;

/**
 * A tenant. The same id the runtime knows as {@code OrganizationId} (§22.2), so
 * one account's executions are separable from another's all the way down.
 */
public record Organization(String id, String name, String planId, Instant createdAt) {
}
