package io.pipemesh.console.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Where the session token lives in a browser.
 *
 * <p>A cookie rather than something JavaScript holds, and {@code HttpOnly} so it
 * stays that way: a token a page can read is a token any script on that page can
 * take. {@code SameSite=Lax} keeps another site from spending it.
 */
@Component
public class SessionCookie {

    public static final String NAME = "pipemesh_session";

    private static final Duration MAX_AGE = Duration.ofDays(14);

    private final boolean secure;

    /**
     * @param secure false only for local development over plain HTTP, where a
     *               secure cookie would never be sent and nothing would work
     */
    public SessionCookie(@Value("${console.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public void attach(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie", cookie(token, MAX_AGE).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
