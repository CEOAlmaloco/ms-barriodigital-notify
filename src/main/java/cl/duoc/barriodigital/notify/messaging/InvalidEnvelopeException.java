package cl.duoc.barriodigital.notify.messaging;

/**
 * El cuerpo no se pudo leer como sobre. Guarda el eventId si el JSON lo traía, para el log mensaje-muerto.
 */
public class InvalidEnvelopeException extends NonRecoverableException {

    private final String eventId;

    public InvalidEnvelopeException(String message, String eventId) {
        super(message);
        this.eventId = eventId;
    }

    public InvalidEnvelopeException(String message, String eventId, Throwable cause) {
        super(message, cause);
        this.eventId = eventId;
    }

    /** eventId leído del cuerpo, o null si no venía o el cuerpo no era JSON. */
    public String eventId() {
        return eventId;
    }
}
