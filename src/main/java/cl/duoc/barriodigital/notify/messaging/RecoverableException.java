package cl.duoc.barriodigital.notify.messaging;

/**
 * Error que puede no repetirse si se intenta de nuevo (contrato EP2-03): el servidor de correo no acepta
 * la conexión o corta a mitad del envío, o falló la escritura del PDF (disco lleno o directorio no disponible).
 * ManualAckProcessor lo reintenta hasta 3 veces y después lo manda a la DLQ con basicNack.
 */
public class RecoverableException extends RuntimeException {

    public RecoverableException(String message) {
        super(message);
    }

    public RecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
