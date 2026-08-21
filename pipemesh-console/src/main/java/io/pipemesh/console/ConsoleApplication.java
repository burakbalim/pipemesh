package io.pipemesh.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The console: sign-up, subscriptions and the screens that manage them.
 *
 * <p>An application that <em>uses</em> PipeMesh, never something PipeMesh uses.
 * The dependency runs one way and only one way — {@code pipemesh-core} is
 * framework-free (§26.2), and nothing here may ever appear on its classpath.
 * {@code ModuleBoundaryTest} holds that rather than this comment.
 */
@SpringBootApplication
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
