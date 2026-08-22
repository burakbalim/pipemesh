package io.pipemesh.runtime;

import io.grpc.Metadata;
import io.pipemesh.core.capability.Principal;
import io.pipemesh.core.execution.OrganizationId;
import io.pipemesh.grpc.PrincipalResolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A runtime on a real port, for the SDK tests in other languages to call.
 *
 * <p>It assembles itself the way the shipped runtime does — same
 * {@link RuntimeAssembly}, same configuration directory (§31) — so what those
 * tests exercise is the thing that gets deployed. It used to wire a server by
 * hand, which meant the SDKs were proven against a runtime nobody runs.
 *
 * <p>Dispatching is off, so {@code start} drives the execution inline and
 * returns where it stopped. That is what an SDK caller sees from a single-node
 * install, and it is what these tests were written against.
 *
 * <p>Prints the bound port on stdout and nothing else — the caller reads that
 * line to know where to connect.
 */
public final class TestRuntimeServer {

    public static void main(String[] args) throws Exception {
        Path config = Path.of("..", "..", "sdk", "testdata").toAbsolutePath().normalize();

        RuntimeSettings settings = RuntimeSettings.from(Map.of(
                RuntimeSettings.CONFIG, System.getenv().getOrDefault(
                        RuntimeSettings.CONFIG, config.toString()),
                RuntimeSettings.PORT, "0",
                RuntimeSettings.DISPATCH, "off")::get);

        // With PIPEMESH_TEST_KEY set, the server identifies callers by an API
        // key, so an SDK test can show that sending one changes the answer.
        // Without it, nothing is identified — which is the default everywhere
        // else and keeps the rest of the suite unchanged.
        String expected = System.getenv("PIPEMESH_TEST_KEY");

        RuntimeAssembly runtime = expected == null
                ? RuntimeAssembly.of(settings).start()
                : RuntimeAssembly.of(
                        settings, keyResolver(expected),
                        List.of("stream:watch"), List.of("event:publish")).start();
        System.out.println(runtime.port());
        System.out.flush();
        runtime.awaitTermination();
    }

    /**
     * The smallest thing that behaves like a real deployment's resolver: one key,
     * one organization, and everything else anonymous.
     *
     * <p>Deliberately not a stand-in for {@code ConsolePrincipalResolver} — that
     * one has its own tests. What this proves is the SDK side: that a key sent
     * over the wire arrives, and that not sending one is refused.
     */
    private static PrincipalResolver keyResolver(String expected) {
        Metadata.Key<String> authorization =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

        return metadata -> {
            String header = metadata.get(authorization);
            if (header == null || !header.equals("Bearer " + expected)) {
                return Principal.ANONYMOUS;
            }
            return new Principal(
                    "test-key", Set.of("stream:watch", "event:publish"), false,
                    OrganizationId.of("acme"));
        };
    }

    private TestRuntimeServer() {
    }
}
