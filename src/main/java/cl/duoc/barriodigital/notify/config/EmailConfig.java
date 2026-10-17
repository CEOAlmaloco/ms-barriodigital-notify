package cl.duoc.barriodigital.notify.config;

import cl.duoc.barriodigital.notify.consumer.email.EmailSender;
import cl.duoc.barriodigital.notify.consumer.email.LoggingEmailSender;
import cl.duoc.barriodigital.notify.consumer.email.SmtpEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties({EmailProperties.class, MailProperties.class})
public class EmailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "barriodigital.email", name = "mode", havingValue = "logging", matchIfMissing = true)
    EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }

    @Bean
    @ConditionalOnProperty(prefix = "barriodigital.email", name = "mode", havingValue = "smtp")
    EmailSender smtpEmailSender(EmailProperties email, MailProperties mail, ObjectProvider<JavaMailSender> sender) {
        if (mail.getHost() == null || mail.getHost().isBlank()) {
            throw new IllegalArgumentException("spring.mail.host es obligatorio en modo smtp");
        }
        if (mail.getPort() != null && (mail.getPort() < 1 || mail.getPort() > 65535)) {
            throw new IllegalArgumentException("spring.mail.port debe estar entre 1 y 65535");
        }
        if (!"smtp".equals(mail.getProtocol())) {
            throw new IllegalArgumentException("spring.mail.protocol debe ser smtp; usa STARTTLS si corresponde");
        }
        if (Boolean.parseBoolean(mail.getProperties().get("mail.smtp.auth"))
                && (mail.getUsername() == null || mail.getUsername().isBlank()
                || mail.getPassword() == null || mail.getPassword().isBlank())) {
            throw new IllegalArgumentException("spring.mail.username y spring.mail.password son obligatorios con autenticacion SMTP");
        }
        for (String property : new String[]{"mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"}) {
            try {
                if (Integer.parseInt(mail.getProperties().get(property)) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("spring.mail.properties[" + property + "] debe ser un entero positivo", e);
            }
        }
        return new SmtpEmailSender(sender.getObject(), email.from().trim());
    }
}
