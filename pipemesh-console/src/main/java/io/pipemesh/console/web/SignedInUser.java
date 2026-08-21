package io.pipemesh.console.web;

import io.pipemesh.console.identity.ConsoleUser;
import io.pipemesh.console.identity.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.Optional;

/**
 * Hands a controller the signed-in user, or refuses the request.
 *
 * <p>A controller that takes a {@link ConsoleUser} parameter cannot be reached
 * without a session — the check is in the type rather than in a line at the top
 * of each method that somebody will one day forget to write.
 */
@Component
public class SignedInUser implements HandlerMethodArgumentResolver {

    private final SessionService sessions;

    public SignedInUser(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ConsoleUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binders) {

        return cookie(request.getNativeRequest(HttpServletRequest.class))
                .flatMap(sessions::userOf)
                .orElseThrow(NotSignedInException::new);
    }

    private Optional<String> cookie(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> SessionCookie.NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst();
    }
}
