package cl.duoc.barriodigital.notify.consumer.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simula el efecto sin registrar destinatario, dirección ni cuerpo. */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage email) {
        log.info("email-simulado type={} entrega-real=false", email.type());
    }
}
