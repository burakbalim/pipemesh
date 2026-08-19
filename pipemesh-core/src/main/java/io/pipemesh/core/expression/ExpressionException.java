package io.pipemesh.core.expression;

/** A condition that cannot be parsed or cannot be evaluated against the data it was given. */
public class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }
}
