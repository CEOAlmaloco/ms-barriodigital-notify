package cl.duoc.barriodigital.notify.consumer.email;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Envía el correo de verdad por SMTP, una vez por llamada.
 * Un rechazo temporal se puede reintentar; una dirección inexistente o una clave incorrecta no. Los reintentos los hace solo ManualAckProcessor.
 */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender sender;
    private final String from;

    /** Guarda el cliente SMTP y la dirección del remitente. */
    public SmtpEmailSender(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    /** Arma un correo de texto UTF-8 y lo envía. Cualquier fallo pasa por classify. */
    @Override
    public void send(EmailMessage email) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.body(), false);
            sender.send(message);
        } catch (MessagingException | RuntimeException e) {
            throw classify(e);
        }
    }

    /**
     * Recorre las causas del fallo SMTP: un 5xx, una autenticación incorrecta o una dirección imposible no se reintenta, y un 4xx o un corte de red sí.
     * Un 4xx gana aunque también haya direcciones inválidas genéricas.
     */
    private static RuntimeException classify(Exception failure) {
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        boolean permanent = false;
        boolean temporary = false;
        boolean invalidAddresses = false;
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) {
                continue;
            }
            if (cause instanceof MailAuthenticationException || cause instanceof AuthenticationFailedException
                    || cause instanceof AddressException) {
                permanent = true;
            }
            if (cause instanceof SMTPAddressFailedException addressFailure) {
                int code = addressFailure.getReturnCode();
                permanent |= code >= 500 && code < 600;
                temporary |= code >= 400 && code < 500;
            }
            if (cause instanceof SMTPSendFailedException sendFailure) {
                int code = sendFailure.getReturnCode();
                permanent |= code >= 500 && code < 600;
                temporary |= code >= 400 && code < 500;
            }
            if (cause instanceof SendFailedException sendFailure && sendFailure.getInvalidAddresses() != null
                    && sendFailure.getInvalidAddresses().length > 0) {
                invalidAddresses = true;
            }
            temporary |= cause instanceof IOException;
            if (cause.getCause() != null) {
                pending.add(cause.getCause());
            }
            if (cause instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.add(messaging.getNextException());
            }
            if (cause instanceof MailSendException mail) {
                pending.addAll(mail.getFailedMessages().values());
            }
        }
        // Un código temporal explícito prevalece sobre una lista genérica de direcciones fallidas.
        if (permanent || (invalidAddresses && !temporary)) {
            return new NonRecoverableException("SMTP rechazo permanente o direccion invalida", failure);
        }
        if (temporary) {
            return new RecoverableException("SMTP fallo temporal de transporte o servidor", failure);
        }
        // Sin clasificación fiable, conserva el tratamiento de excepciones inesperadas de EP2-15.
        return failure instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("SMTP fallo no clasificado", failure);
    }
}
