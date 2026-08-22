package io.pipemesh.grpc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proto is the authoritative contract (§26.1), so a published field may not
 * change meaning. This is what stops it, rather than somebody noticing.
 *
 * <p>Narrow on purpose. It extracts, for every message, the set of
 * {@code number:name:type} entries and refuses any that disappeared or changed.
 * Additions are free. That is not full protobuf semantics and does not claim to
 * be — it is the same kind of deliberately small language this codebase uses for
 * conditions and schemas, checking the one property §26.1 protects.
 *
 * <p>{@code buf breaking} is the real tool. It is not used because this
 * repository builds offline, and a second binary is a setup step for everyone
 * who clones it.
 */
class ProtoCompatibilityTest {

    private static final Pattern MESSAGE = Pattern.compile("^\\s*message\\s+(\\w+)\\s*\\{");
    private static final Pattern FIELD =
            Pattern.compile("^\\s*(?:repeated\\s+)?([\\w.]+)\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern RESERVED = Pattern.compile("^\\s*reserved\\s+(.+);");
    private static final Pattern CLOSE = Pattern.compile("^\\s*}");

    private static Path repository() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    @Test
    void nothingPublishedWasRemovedOrChanged() throws IOException {
        Map<String, Map<Integer, String>> released =
                fieldsOf(repository().resolve("release/proto/pipemesh.proto"));
        Map<String, Map<Integer, String>> current =
                fieldsOf(repository().resolve("proto/pipemesh.proto"));
        Map<String, List<String>> reserved =
                reservedOf(repository().resolve("proto/pipemesh.proto"));

        List<String> broken = new ArrayList<>();
        released.forEach((message, fields) -> fields.forEach((number, shape) -> {
            Map<Integer, String> now = current.get(message);
            if (now == null) {
                broken.add("message " + message + " is gone");
                return;
            }
            String today = now.get(number);
            if (today == null) {
                // Retired properly: the number is reserved, so nothing can reuse
                // it and no client is surprised. That is the correct way to
                // remove a field, and punishing it would push people to the
                // wrong one.
                if (!isReserved(reserved.get(message), number)) {
                    broken.add(message + "." + shape + " was removed without reserving " + number);
                }
                return;
            }
            if (!today.equals(shape)) {
                broken.add(message + " field " + number + " changed from " + shape + " to " + today);
            }
        }));

        assertTrue(broken.isEmpty(),
                "the proto is the contract, and these break it:\n  " + String.join("\n  ", broken));
    }

    private static Map<String, Map<Integer, String>> fieldsOf(Path proto) throws IOException {
        Map<String, Map<Integer, String>> messages = new LinkedHashMap<>();
        String message = null;

        for (String line : Files.readAllLines(proto)) {
            Matcher opening = MESSAGE.matcher(line);
            if (opening.find()) {
                message = opening.group(1);
                messages.put(message, new LinkedHashMap<>());
                continue;
            }
            if (message == null) {
                continue;
            }
            if (CLOSE.matcher(line).find()) {
                message = null;
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (field.find()) {
                messages.get(message).put(
                        Integer.parseInt(field.group(3)),
                        field.group(2) + ":" + field.group(1));
            }
        }
        return messages;
    }

    private static Map<String, List<String>> reservedOf(Path proto) throws IOException {
        Map<String, List<String>> reserved = new LinkedHashMap<>();
        String message = null;

        for (String line : Files.readAllLines(proto)) {
            Matcher opening = MESSAGE.matcher(line);
            if (opening.find()) {
                message = opening.group(1);
                reserved.put(message, new ArrayList<>());
                continue;
            }
            if (message == null) {
                continue;
            }
            if (CLOSE.matcher(line).find()) {
                message = null;
                continue;
            }
            Matcher entry = RESERVED.matcher(line);
            if (entry.find()) {
                reserved.get(message).add(entry.group(1));
            }
        }
        return reserved;
    }

    /** Handles both {@code reserved 5;} and {@code reserved 5 to 9;}. */
    private static boolean isReserved(List<String> entries, int number) {
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            for (String part : entry.split(",")) {
                String range = part.trim();
                if (range.matches("\\d+") && Integer.parseInt(range) == number) {
                    return true;
                }
                Matcher span = Pattern.compile("(\\d+)\\s+to\\s+(\\d+)").matcher(range);
                if (span.find()
                        && number >= Integer.parseInt(span.group(1))
                        && number <= Integer.parseInt(span.group(2))) {
                    return true;
                }
            }
        }
        return false;
    }
}
