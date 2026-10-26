package cl.duoc.barriodigital.notify.consumer.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simula el envío del correo.
 * Solo anota en el log el tipo de aviso. No escribe el destinatario ni el texto.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    /** Anota en el log el tipo del evento. No escribe destinatario, dirección ni cuerpo. */
    @Override
    public void send(EmailMessage email) {
        log.info("email-simulado type={} entrega-real=false", email.type());
    }
}
