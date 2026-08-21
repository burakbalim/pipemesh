package io.pipemesh.console.identity;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Sends the verification link by email.
 *
 * <p>{@code @Primary} rather than {@code @ConditionalOnMissingBean}: that
 * annotation means nothing on a {@code @Component} and would quietly do nothing
 * — a lesson this codebase has already paid for once.
 *
 * <p>Present only when {@code console.mail.host} is set, so a development
 * install keeps the logging sender and says loudly what that means.
 */
@Component
@Primary
@ConditionalOnProperty("console.mail.host")
public class SmtpVerificationLinkSender implements VerificationLinkSender {

    private final Properties settings = new Properties();
    private final String from;
    private final String baseUrl;
    private final String username;
    private final String password;

    public SmtpVerificationLinkSender(
            @Value("${console.mail.host}") String host,
            @Value("${console.mail.port:587}") int port,
            @Value("${console.mail.from}") String from,
            @Value("${console.mail.username:}") String username,
            @Value("${console.mail.password:}") String password,
            @Value("${console.baseUrl}") String baseUrl) {

        this.from = from;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;

        settings.put("mail.smtp.host", host);
        settings.put("mail.smtp.port", String.valueOf(port));
        settings.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        // STARTTLS rather than plaintext: the message carries a link that is, for
        // the next day, the account.
        settings.put("mail.smtp.starttls.enable", "true");
    }

    @Override
    public void send(String email, String token) {
        try {
            MimeMessage message = new MimeMessage(session());
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email));
            message.setSubject("Confirm your PipeMesh account");
            message.setText(body(token));

            Transport.send(message);
        } catch (Exception failure) {
            // Registration already wrote the account; the link can be reissued.
            // Losing the exception would leave somebody waiting for a mail that
            // was never sent and nothing anywhere saying so.
            throw new VerificationMailFailedException(email, failure);
        }
    }

    private Session session() {
        if (username.isBlank()) {
            return Session.getInstance(settings);
        }
        return Session.getInstance(settings, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(username, password);
            }
        });
    }

    private String body(String token) {
        return """
                Welcome to PipeMesh.

                Confirm your address by opening this link:

                %s/#/verify?token=%s

                It works once, and expires in a day. If you did not create an
                account, nothing happens if you ignore this.
                """.formatted(baseUrl, token);
    }
}
