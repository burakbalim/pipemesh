package io.pipemesh.postgres;

/** A database failure the caller cannot do anything about except retry or give up. */
public class StateStoreException extends RuntimeException {

    public StateStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
