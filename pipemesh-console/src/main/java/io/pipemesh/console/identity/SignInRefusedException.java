package io.pipemesh.console.identity;

/**
 * The address or the password was wrong.
 *
 * <p>Deliberately one exception for both. Telling them apart would turn the
 * sign-in form into a way to ask which addresses have accounts.
 */
public class SignInRefusedException extends RuntimeException {

    public SignInRefusedException() {
        super("email or password is incorrect");
    }
}
