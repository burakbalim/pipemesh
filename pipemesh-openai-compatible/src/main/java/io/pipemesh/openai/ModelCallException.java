package io.pipemesh.openai;

/** The model endpoint refused, timed out, or answered with something unusable. */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message) {
        super(message);
    }

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
