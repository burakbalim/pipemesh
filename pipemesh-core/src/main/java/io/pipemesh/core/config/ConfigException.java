package io.pipemesh.core.config;

/** A configuration repository that cannot be read, or does not describe a valid runtime. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
