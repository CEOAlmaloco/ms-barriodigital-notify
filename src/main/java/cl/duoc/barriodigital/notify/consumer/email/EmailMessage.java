package cl.duoc.barriodigital.notify.consumer.email;

/** Correo de texto plano ya compuesto. Ambos senders reciben el mismo contenido. */
public record EmailMessage(String type, String to, String subject, String body) {
}
