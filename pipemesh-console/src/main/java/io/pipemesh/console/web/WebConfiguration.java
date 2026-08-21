package io.pipemesh.console.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers the argument resolver that makes a session a method parameter. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final SignedInUser signedInUser;

    public WebConfiguration(SignedInUser signedInUser) {
        this.signedInUser = signedInUser;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(signedInUser);
    }
}
