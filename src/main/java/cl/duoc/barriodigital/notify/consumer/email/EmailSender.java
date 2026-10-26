package cl.duoc.barriodigital.notify.consumer.email;

/**
 * Contrato de envío de un correo ya escrito.
 * Quien lo usa no necesita saber si el envío es simulado o sale por SMTP.
 */
@FunctionalInterface
public interface EmailSender {

    /** Envía un correo ya escrito. Puede lanzar un error recuperable o no recuperable. */
    void send(EmailMessage email);
}
