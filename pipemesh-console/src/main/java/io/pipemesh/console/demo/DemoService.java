package io.pipemesh.console.demo;

import com.google.protobuf.Struct;
import io.pipemesh.console.apikey.ApiKeyService;
import io.pipemesh.console.apikey.IssuedApiKey;
import io.pipemesh.proto.v1.ExecutionHandle;
import io.pipemesh.proto.v1.ExecutionUpdate;
import io.pipemesh.proto.v1.StartExecutionRequest;
import io.pipemesh.proto.v1.WatchExecutionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/** Running the example workflow, on the account of whoever pressed the button. */
@Service
public class DemoService {

    private final ApiKeyService keys;
    private final RuntimeClient runtime;
    private final String workflowId;

    public DemoService(
            ApiKeyService keys, RuntimeClient runtime,
            @Value("${console.demo.workflow:demo}") String workflowId) {

        this.keys = keys;
        this.runtime = runtime;
        this.workflowId = workflowId;
    }

    /**
     * Runs the demo and hands every update to {@code onUpdate} as it arrives.
     *
     * <p>A key is issued for this one run and revoked when it ends. The console
     * cannot reuse a person's own key — it stores only hashes, by design — and
     * the alternative, letting the console act as an organization without one,
     * would be a private door into the runtime that exists solely for a demo.
     *
     * <p>So the demo takes the same road as production: an API key, the same
     * boundary, the same quota, the same permissions. If it works here it works
     * there, which is the only reason a demo is worth having.
     */
    public void run(String organizationId, Map<String, String> input, Consumer<ExecutionUpdate> onUpdate) {
        IssuedApiKey issued = keys.issue(organizationId, "demo run");
        try {
            ExecutionHandle handle = runtime.start(issued.secret(), StartExecutionRequest.newBuilder()
                    .setWorkflowId(workflowId)
                    .setOrganizationId(organizationId)
                    .setInput(structOf(input))
                    .build());

            Iterator<ExecutionUpdate> updates = runtime.watch(
                    issued.secret(),
                    WatchExecutionRequest.newBuilder()
                            .setExecutionId(handle.getExecutionId())
                            .build());

            while (updates.hasNext()) {
                onUpdate.accept(updates.next());
            }
        } finally {
            keys.revoke(issued.key().id(), organizationId);
        }
    }

    /** Fully qualified: protobuf's Value and Spring's @Value are both in scope. */
    private static Struct structOf(Map<String, String> input) {
        Struct.Builder struct = Struct.newBuilder();
        input.forEach((field, value) -> struct.putFields(field,
                com.google.protobuf.Value.newBuilder().setStringValue(value).build()));
        return struct.build();
    }
}
