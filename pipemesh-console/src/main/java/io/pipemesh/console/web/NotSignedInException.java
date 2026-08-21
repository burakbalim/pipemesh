package io.pipemesh.console.web;

/** The request carried no usable session. */
public class NotSignedInException extends RuntimeException {

    public NotSignedInException() {
        super("sign in first");
    }
}
