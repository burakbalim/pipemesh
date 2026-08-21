package io.pipemesh.console.identity;

/**
 * The password was right but the address was never confirmed.
 *
 * <p>Separate from {@link SignInRefusedException} on purpose: the person already
 * proved they know the password, so nothing is revealed by telling them what is
 * actually wrong — and "email or password is incorrect" would be a lie that
 * costs them an afternoon.
 */
public class UnverifiedAccountException extends RuntimeException {

    public UnverifiedAccountException() {
        super("this account has not been verified yet; check the link we emailed you");
    }
}
