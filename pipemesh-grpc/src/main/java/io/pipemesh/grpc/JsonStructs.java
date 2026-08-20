package io.pipemesh.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;

/**
 * Moves JSON across the wire as {@code google.protobuf.Struct}.
 *
 * <p>Struct is the one protobuf type that can carry a workflow's variables
 * without the schema knowing their shape — which is the whole point, since a
 * workflow's variables are whatever its steps decided to write.
 */
final class JsonStructs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonStructs() {
    }

    static Struct toStruct(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Struct.getDefaultInstance();
        }
        try {
            Struct.Builder struct = Struct.newBuilder();
            JsonFormat.parser().merge(node.toString(), struct);
            return struct.build();
        } catch (Exception malformed) {
            throw new IllegalArgumentException("could not convert JSON to Struct", malformed);
        }
    }

    static ObjectNode toJson(Struct struct) {
        if (struct == null || struct.getFieldsCount() == 0) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return (ObjectNode) JSON.readTree(JsonFormat.printer().print(struct));
        } catch (Exception malformed) {
            throw new IllegalArgumentException("could not convert Struct to JSON", malformed);
        }
    }
}
