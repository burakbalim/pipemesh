package io.pipemesh.console;

import io.pipemesh.console.identity.LoggingVerificationLinkSender;
import io.pipemesh.console.identity.VerificationLinkSender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a shared deployment that is only safe on a laptop.
 *
 * <p>{@code LoggingVerificationLinkSender} writes account links to the log. On a
 * single machine that is a convenience; on a shared deployment it means anybody
 * who can read the logs can take over every account being registered — and a
 * console that runs while doing that is worse than one that does not run at all,
 * because nobody finds out.
 *
 * <p>So the strictness is opt-in by environment rather than by remembering:
 * {@code console.cloud=true} says this deployment is shared, and the checks that
 * only matter there are enforced there.
 */
@Component
public class CloudReadiness implements InitializingBean {

    private final boolean cloud;
    private final VerificationLinkSender sender;

    public CloudReadiness(
            @Value("${console.cloud:false}") boolean cloud, VerificationLinkSender sender) {

        this.cloud = cloud;
        this.sender = sender;
    }

    @Override
    public void afterPropertiesSet() {
        if (!cloud) {
            return;
        }
        if (sender instanceof LoggingVerificationLinkSender) {
            throw new IllegalStateException("""
                    console.cloud is true but no mail sender is configured, so verification \
                    links would be written to the log where anybody reading it could take \
                    over new accounts. Set console.mail.host, console.mail.from and \
                    console.baseUrl, or run with console.cloud=false if this deployment is \
                    a single machine you own.""");
        }
    }
}
