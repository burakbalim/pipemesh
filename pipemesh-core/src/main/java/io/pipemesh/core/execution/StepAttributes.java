package io.pipemesh.core.execution;

/**
 * Attribute names a step may report about its own run.
 *
 * <p>These are a naming convention, in the spirit of OpenTelemetry's semantic
 * conventions — not a list of things the engine understands. The engine copies
 * whatever a step reports into the step history and lifts these four into typed
 * columns because they are worth querying. A step type the engine has never
 * heard of can report whatever it likes and lose nothing.
 */
public final class StepAttributes {

    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_PROMPT_VERSION = "llm.prompt_version";
    public static final String LLM_INPUT_TOKENS = "llm.input_tokens";
    public static final String LLM_OUTPUT_TOKENS = "llm.output_tokens";

    public static final String CAPABILITY_ID = "capability.id";
    public static final String CAPABILITY_EXECUTION_TYPE = "capability.execution_type";

    private StepAttributes() {
    }
}
