package io.pipemesh.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The runtime as a process (§26.3).
 *
 * <pre>
 * PIPEMESH_CONFIG=/etc/pipemesh java -jar pipemesh-runtime.jar
 * PIPEMESH_CONFIG=/etc/pipemesh java -jar pipemesh-runtime.jar --migrate-only
 * </pre>
 *
 * <p>It composes rather than decides. Which database, which port, whether this
 * process drives executions — all read from the environment, so the same image
 * is a single-node install, an API-only pod, or a pool of drivers, without a
 * branch anywhere inside the runtime itself.
 */
public final class RuntimeMain {

    private static final Logger log = LoggerFactory.getLogger(RuntimeMain.class);

    /**
     * Applies the schema and exits, for a deployment that migrates as its own
     * step rather than on the way up. Startup migration stays safe either way —
     * the advisory lock makes both orders correct.
     */
    private static final String MIGRATE_ONLY = "--migrate-only";

    public static void main(String[] args) throws Exception {
        RuntimeSettings settings = RuntimeSettings.fromEnvironment();

        if (List.of(args).contains(MIGRATE_ONLY)) {
            RuntimeAssembly.migrate(settings);
            return;
        }

        RuntimeAssembly.migrate(settings);
        try (RuntimeAssembly runtime = RuntimeAssembly.of(settings).start()) {
            runtime.awaitTermination();
        } catch (Exception failure) {
            log.error("The runtime stopped", failure);
            throw failure;
        }
    }

    private RuntimeMain() {
    }
}
