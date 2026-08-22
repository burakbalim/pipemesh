package io.pipemesh.console.web;

import io.pipemesh.console.identity.EmailAlreadyRegisteredException;
import io.pipemesh.console.identity.InvalidVerificationException;
import io.pipemesh.console.identity.SignInRefusedException;
import io.pipemesh.console.identity.UnverifiedAccountException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One shape for every refusal.
 *
 * <p>{@code code} is what a screen branches on; {@code message} is what a person
 * reads. Branching on prose is how a message becomes impossible to reword.
 */
@RestControllerAdvice
public class ApiErrors {

    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> emailTaken(EmailAlreadyRegisteredException failure) {
        return answer(HttpStatus.CONFLICT, "email_already_registered", failure);
    }

    @ExceptionHandler(InvalidVerificationException.class)
    public ResponseEntity<ApiError> badLink(InvalidVerificationException failure) {
        return answer(HttpStatus.GONE, "verification_invalid", failure);
    }

    @ExceptionHandler(SignInRefusedException.class)
    public ResponseEntity<ApiError> refused(SignInRefusedException failure) {
        return answer(HttpStatus.UNAUTHORIZED, "sign_in_refused", failure);
    }

    /**
     * Separate from a refusal: the password was right, so this is an instruction
     * rather than a rejection, and a screen should say something different.
     */
    @ExceptionHandler(UnverifiedAccountException.class)
    public ResponseEntity<ApiError> unverified(UnverifiedAccountException failure) {
        return answer(HttpStatus.FORBIDDEN, "account_unverified", failure);
    }

    @ExceptionHandler(NotSignedInException.class)
    public ResponseEntity<ApiError> notSignedIn(NotSignedInException failure) {
        return answer(HttpStatus.UNAUTHORIZED, "not_signed_in", failure);
    }

    @ExceptionHandler(UnknownApiKeyException.class)
    public ResponseEntity<ApiError> unknownKey(UnknownApiKeyException failure) {
        return answer(HttpStatus.NOT_FOUND, "api_key_unknown", failure);
    }

    /**
     * A webhook that did not verify.
     *
     * <p>{@code 400} rather than {@code 401}: there is nothing to authenticate
     * with here and nothing to retry. A provider that gets this has sent
     * something we will never accept.
     */
    @ExceptionHandler(io.pipemesh.console.billing.InvalidWebhookException.class)
    public ResponseEntity<ApiError> badWebhook(
            io.pipemesh.console.billing.InvalidWebhookException failure) {

        return answer(HttpStatus.BAD_REQUEST, "webhook_invalid", failure);
    }

    private ResponseEntity<ApiError> answer(HttpStatus status, String code, Exception failure) {
        return ResponseEntity.status(status).body(new ApiError(code, failure.getMessage()));
    }
}
