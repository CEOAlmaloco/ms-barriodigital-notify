package cl.duoc.barriodigital.notify.messaging;

/**
 * Fallo que puede salir bien al reintentar: el correo no conecta o se corta, o el disco no puede guardar el PDF.
 * ManualAckProcessor lo intenta hasta 3 veces en el mismo hilo y, si se agota, hace basicNack con requeue=false.
 */
public class RecoverableException extends RuntimeException {

    /** Error recuperable, solo con el mensaje. */
    public RecoverableException(String message) {
        super(message);
    }

    /** Error recuperable, con el mensaje y la causa técnica. */
    public RecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
