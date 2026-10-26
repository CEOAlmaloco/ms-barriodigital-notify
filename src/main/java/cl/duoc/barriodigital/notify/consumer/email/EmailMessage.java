package cl.duoc.barriodigital.notify.consumer.email;

/**
 * Correo ya escrito: tipo de evento, destinatario, asunto y texto.
 * El sender simulado y el SMTP reciben el mismo contenido.
 */
public record EmailMessage(String type, String to, String subject, String body) {
}
