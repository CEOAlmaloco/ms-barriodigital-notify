package cl.duoc.barriodigital.notify.messaging;

/**
 * Fallo que no cambia al reintentar: el cuerpo no es un mensaje válido, el type no es uno de los seis, o falta un dato que no se puede inventar.
 * ManualAckProcessor lo descarta con basicReject y requeue=false, sin reintentos.
 */
public class NonRecoverableException extends RuntimeException {

    /** Error definitivo, solo con el mensaje. */
    public NonRecoverableException(String message) {
        super(message);
    }

    /** Error definitivo, con el mensaje y la causa técnica. */
    public NonRecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
