package io.pipemesh.core.intent;

/**
 * The message did not settle on an intent.
 *
 * <p>An exception rather than a "nearest match", because running the closest
 * workflow to what someone might have meant is worse than saying no. The runtime
 * decides how execution proceeds; deciding what someone meant is not the same
 * thing as guessing (§20).
 */
public class IntentUnresolvedException extends RuntimeException {

    public IntentUnresolvedException(String message) {
        super(message);
    }
}
