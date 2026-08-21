package io.pipemesh.console.identity;

/** Someone already opened an account with this address. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("an account already exists for " + email);
    }
}
