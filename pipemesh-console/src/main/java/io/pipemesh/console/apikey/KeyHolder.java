package io.pipemesh.console.apikey;

import java.util.List;

/**
 * What a presented key resolves to: whose it is, and what their plan allows.
 *
 * <p>Permissions come from the plan rather than from the key, so changing what a
 * subscription includes changes what every key on it can do — without reissuing
 * anything.
 */
public record KeyHolder(String keyId, String organizationId, List<String> permissions) {
}
