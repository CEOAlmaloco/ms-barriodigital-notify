package cl.duoc.barriodigital.notify.messaging;

/**
 * El cuerpo no se pudo leer como mensaje.
 * Si el JSON traía eventId, lo guarda para el log mensaje-muerto.
 */
public class InvalidEnvelopeException extends NonRecoverableException {

    private final String eventId;

    /** Crea el error y, si el JSON traía eventId, lo guarda para el log. */
    public InvalidEnvelopeException(String message, String eventId) {
        super(message);
        this.eventId = eventId;
    }

    /** Igual que el otro constructor, y además guarda la causa técnica. */
    public InvalidEnvelopeException(String message, String eventId, Throwable cause) {
        super(message, cause);
        this.eventId = eventId;
    }

    /** eventId leído del cuerpo, o null si no venía o el cuerpo no era JSON. */
    public String eventId() {
        return eventId;
    }
}
