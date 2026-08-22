package io.pipemesh.grpc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Five artifacts will carry a version, and generated code is committed. Both are
 * things that drift quietly, so both are checked rather than generated.
 *
 * <p>Generating would be the obvious answer and is the weaker one: a generator
 * that did not run leaves the files disagreeing and says nothing. A check fails
 * the build the moment they disagree, whoever forgot.
 */
class ReleaseConsistencyTest {

    private static Path repository() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static String version() throws IOException {
        return Files.readString(repository().resolve("VERSION")).trim();
    }

    @Test
    void everyArtifactCarriesTheSameVersion() throws IOException {
        String version = version();

        // Maven marks development builds; the number in front of it is the one
        // being compared, or this test is either always red or meaninglessly lax.
        String maven = between(
                Files.readString(repository().resolve("pom.xml")),
                "<artifactId>pipemesh</artifactId>\n    <version>", "</version>")
                .replace("-SNAPSHOT", "");

        String python = between(
                Files.readString(repository().resolve("sdk/python/pyproject.toml")),
                "version = \"", "\"");

        String typescript = between(
                Files.readString(repository().resolve("sdk/typescript/package.json")),
                "\"version\": \"", "\"");

        assertEquals(version, maven, "pom.xml");
        assertEquals(version, python, "sdk/python/pyproject.toml");
        assertEquals(version, typescript, "sdk/typescript/package.json");
    }

    /**
     * The SDKs ship generated stubs, so a proto change that nobody regenerated
     * is a package that disagrees with the server it talks to.
     *
     * <p>#9 already showed this: the Python stubs were not regenerated and
     * eighteen tests failed. Tests caught it that day. At release time they would
     * not — the user would.
     *
     * <p>A recorded hash rather than regeneration here: regenerating needs protoc
     * and a Python toolchain, and the natural way to update this file is to
     * regenerate.
     */
    @Test
    void theCommittedStubsWereGeneratedFromThisProto() throws IOException {
        String proto = sha256(repository().resolve("proto/pipemesh.proto"));

        assertEquals(proto,
                Files.readString(repository().resolve("sdk/python/pipemesh/proto.sha256")).trim(),
                "regenerate the Python stubs, then update sdk/python/pipemesh/proto.sha256");
        assertEquals(proto,
                Files.readString(repository().resolve("sdk/typescript/src/proto.sha256")).trim(),
                "the TypeScript package copies the proto at build time; update its hash");
    }

    @Test
    void theReleasedProtoCopyExists() {
        assertTrue(Files.isRegularFile(repository().resolve("release/proto/pipemesh.proto")),
                "the compatibility check has nothing to compare against without it");
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) {
            throw new IllegalStateException("could not find '" + start + "'");
        }
        from += start.length();
        return text.substring(from, text.indexOf(end, from));
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
