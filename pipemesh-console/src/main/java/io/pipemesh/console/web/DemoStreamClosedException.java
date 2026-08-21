package io.pipemesh.console.web;

/** The browser went away mid-stream, which is not a failure of anything. */
public class DemoStreamClosedException extends RuntimeException {

    public DemoStreamClosedException(Throwable cause) {
        super("the demo stream was closed by the client", cause);
    }
}
