package io.pipemesh.console.web;

import io.pipemesh.console.identity.ConsoleUser;
import io.pipemesh.console.identity.Organization;
import io.pipemesh.console.identity.RegistrationService;
import io.pipemesh.console.identity.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening an account and getting into it.
 *
 * <p>HTTP only: parse, call one service method, shape the answer. Every rule
 * about what may happen lives in the services.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final RegistrationService registrations;
    private final SessionService sessions;
    private final SessionCookie cookie;

    public AccountController(
            RegistrationService registrations, SessionService sessions, SessionCookie cookie) {

        this.registrations = registrations;
        this.sessions = sessions;
        this.cookie = cookie;
    }

    public record RegistrationRequest(String organizationName, String email, String password) {
    }

    public record OrganizationView(String id, String name, String planId) {

        static OrganizationView of(Organization organization) {
            return new OrganizationView(organization.id(), organization.name(), organization.planId());
        }
    }

    public record SignInRequest(String email, String password) {
    }

    public record UserView(String id, String email, String organizationId) {
    }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationView register(@RequestBody RegistrationRequest request) {
        return OrganizationView.of(registrations.register(
                request.organizationName(), request.email(), request.password()));
    }

    /**
     * Claims a verification link.
     *
     * <p>A POST rather than a GET, because a link that acts on being fetched is a
     * link a mail client's preview can spend.
     */
    @PostMapping("/verifications/{token}")
    public void verify(@PathVariable String token) {
        registrations.verify(token);
    }

    @PostMapping("/sessions")
    public UserView signIn(@RequestBody SignInRequest request, HttpServletResponse response) {
        String token = sessions.signIn(request.email(), request.password());
        cookie.attach(response, token);

        ConsoleUser user = sessions.userOf(token).orElseThrow();
        return new UserView(user.id(), user.email(), user.organizationId());
    }

    @DeleteMapping("/sessions")
    public void signOut(
            @CookieValue(name = SessionCookie.NAME, required = false) String token,
            HttpServletResponse response) {

        if (token != null) {
            sessions.signOut(token);
        }
        cookie.clear(response);
    }

    /** Who the browser is, or 401 — the call every screen makes on load. */
    @GetMapping("/session")
    public ResponseEntity<UserView> current(
            @CookieValue(name = SessionCookie.NAME, required = false) String token) {

        return (token == null ? java.util.Optional.<ConsoleUser>empty() : sessions.userOf(token))
                .map(user -> ResponseEntity.ok(
                        new UserView(user.id(), user.email(), user.organizationId())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
