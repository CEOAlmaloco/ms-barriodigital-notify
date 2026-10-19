package cl.duoc.barriodigital.notify.messaging;

/**
 * Error que no cambia al reintentar (contrato EP2-03): el cuerpo no es JSON o no trae el sobre
 * (type, eventId, payload), el type no es uno de los seis del contrato EP2-01, o falta un dato que el caso
 * no puede inventar (solicitanteEmail en un email, address en crew.ticket).
 * ManualAckProcessor lo manda a la DLQ con basicReject, sin reintentos.
 */
public class NonRecoverableException extends RuntimeException {

    public NonRecoverableException(String message) {
        super(message);
    }

    public NonRecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
