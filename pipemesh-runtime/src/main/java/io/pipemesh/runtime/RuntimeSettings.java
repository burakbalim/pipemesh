package io.pipemesh.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everything the runtime is told from outside its own files.
 *
 * <p>Read from the environment rather than from the configuration directory,
 * because these are the things that differ between one deployment of the same
 * configuration and the next: which database, which port, whether this process
 * drives work or only serves it.
 *
 * <p>Dispatching and starting inline are separate questions, and tying them
 * together was wrong. "Does this process run a driver loop" is one; "does
 * {@code start} drive the execution on the caller's thread" is another. A cloud
 * API replica answers no to both — it neither dispatches nor drives, because
 * other processes do. The default still couples them, because a lone process
 * that answers no to both would accept work and never run it.
 *
 * <p>Recovery and dispatch have separate intervals because they answer different
 * questions. Recovery asks "did a process die", which is rare and expensive to
 * check. Dispatch asks "is there work", which is constant and one indexed query
 * — sharing recovery's minute would make a single node look dead for up to a
 * minute after every request.
 *
 * <p>Secrets are not here either. A model's key is named by
 * {@code ModelDefinition.secretFromEnvironment}, so the file says which variable
 * holds it and never the value — files are shared, secrets are not.
 */
public record RuntimeSettings(
        Path configDirectory,
        int port,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        Duration recoveryInterval,
        Duration dispatchInterval,
        boolean dispatching,
        boolean startsInline) {

    public static final String CONFIG = "PIPEMESH_CONFIG";
    public static final String PORT = "PIPEMESH_PORT";
    public static final String DB_URL = "PIPEMESH_DB_URL";
    public static final String DB_USER = "PIPEMESH_DB_USER";
    public static final String DB_PASSWORD = "PIPEMESH_DB_PASSWORD";
    public static final String RECOVERY_INTERVAL = "PIPEMESH_RECOVERY_INTERVAL";
    public static final String DISPATCH = "PIPEMESH_DISPATCH";
    public static final String DISPATCH_INTERVAL = "PIPEMESH_DISPATCH_INTERVAL";
    public static final String START = "PIPEMESH_START";

    public static RuntimeSettings fromEnvironment() {
        return from(System::getenv);
    }

    /** Takes the lookup as an argument, so a test can supply an environment. */
    public static RuntimeSettings from(Function<String, String> environment) {
        boolean dispatching = !"off".equalsIgnoreCase(value(environment, DISPATCH, "on"));
        String config = value(environment, CONFIG, null);
        if (config == null) {
            throw new IllegalStateException(
                    CONFIG + " is required: it names the directory of workflows, models,"
                            + " capabilities and prompts this runtime serves (§31)");
        }

        return new RuntimeSettings(
                Path.of(config),
                Integer.parseInt(value(environment, PORT, "8080")),
                value(environment, DB_URL, null),
                value(environment, DB_USER, "pipemesh"),
                value(environment, DB_PASSWORD, ""),
                Duration.parse("PT" + value(environment, RECOVERY_INTERVAL, "1M")),
                Duration.parse("PT" + value(environment, DISPATCH_INTERVAL, "1S")),
                dispatching,
                startsInline(environment, dispatching));
    }

    /**
     * Whether execution state outlives this process.
     *
     * <p>Without a database the runtime still runs, which is what makes trying it
     * easy — and it is exactly what nobody should discover in production, so the
     * absence is announced rather than assumed.
     */
    public boolean durable() {
        return databaseUrl != null && !databaseUrl.isBlank();
    }

    public Optional<String> databaseUrlIfAny() {
        return Optional.ofNullable(durable() ? databaseUrl : null);
    }

    /**
     * Inline unless told otherwise, and "otherwise" defaults to whether this
     * process dispatches: a process that drives work should not also drive it on
     * the caller's thread, and a process that does neither would be a runtime
     * that accepts work and forgets it.
     */
    private static boolean startsInline(Function<String, String> environment, boolean dispatching) {
        String mode = value(environment, START, null);
        return mode == null ? !dispatching : "inline".equalsIgnoreCase(mode);
    }

    private static String value(Function<String, String> environment, String name, String fallback) {
        String read = environment.apply(name);
        return read == null || read.isBlank() ? fallback : read;
    }
}
