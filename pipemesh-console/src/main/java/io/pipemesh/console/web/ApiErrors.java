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

    private ResponseEntity<ApiError> answer(HttpStatus status, String code, Exception failure) {
        return ResponseEntity.status(status).body(new ApiError(code, failure.getMessage()));
    }
}
