package io.pipemesh.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console depends on the runtime. The runtime must never depend on the
 * console (§26.2).
 *
 * <p>Held by a test rather than by a comment, because a module pointed the wrong
 * way is cheap to add and expensive to undo — by the time anyone notices, the
 * framework-free promise the runtime makes has already been broken for every
 * embedder who inherited it.
 */
class ModuleBoundaryTest {

    /**
     * The library modules. {@code pipemesh-runtime} and {@code pipemesh-console}
     * are applications that compose them, and nothing here may point at either.
     */
    private static final List<String> RUNTIME_MODULES = List.of(
            "pipemesh-core", "pipemesh-postgres", "pipemesh-grpc",
            "pipemesh-mcp", "pipemesh-openai-compatible", "pipemesh-opentelemetry");

    @Test
    void noRuntimeModuleDependsOnTheConsole() throws IOException {
        Path repository = Path.of("..").toAbsolutePath().normalize();

        for (String module : RUNTIME_MODULES) {
            String pom = Files.readString(repository.resolve(module).resolve("pom.xml"));

            assertTrue(!pom.contains("pipemesh-console"),
                    module + " depends on the console; the dependency runs one way only");
            assertTrue(!pom.contains("pipemesh-runtime"),
                    module + " depends on the runtime application; the dependency runs one way only");
        }
    }

    /** Spring is the console's business. A runtime module carrying it is the same leak. */
    @Test
    void noRuntimeModuleCarriesSpring() throws IOException {
        Path repository = Path.of("..").toAbsolutePath().normalize();

        for (String module : RUNTIME_MODULES) {
            String pom = Files.readString(repository.resolve(module).resolve("pom.xml"));

            assertTrue(!pom.contains("org.springframework"),
                    module + " carries Spring; core/ is framework-free by design");
        }
    }

    /**
     * The console composes the runtime library modules; it must not inherit the
     * runnable runtime. Two applications, side by side — not one built on the
     * other (§26.3).
     */
    @Test
    void theConsoleDoesNotDependOnTheRunnableRuntime() throws IOException {
        String pom = Files.readString(Path.of("pom.xml").toAbsolutePath());

        assertTrue(!pom.contains("pipemesh-runtime"),
                "the console is a composition beside the runtime, not one built on it");
    }

    @Test
    void theConsoleIsTheOnlyModuleAllowedToDependOnTheRuntime() throws IOException {
        Path console = Path.of("pom.xml").toAbsolutePath();

        String pom = Files.readString(console);

        assertTrue(pom.contains("pipemesh-postgres"),
                "this test is meaningless if the console stops depending on the runtime at all");
    }
}
