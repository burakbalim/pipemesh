package io.pipemesh.runtime;

import java.nio.file.Path;
import java.util.Map;

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

        RuntimeAssembly runtime = RuntimeAssembly.of(settings).start();
        System.out.println(runtime.port());
        System.out.flush();
        runtime.awaitTermination();
    }

    private TestRuntimeServer() {
    }
}
