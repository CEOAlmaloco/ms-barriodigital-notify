package cl.duoc.barriodigital.notify.messaging;

/**
 * Trabajo de un consumidor sobre un mensaje ya leído.
 * NonRecoverableException no se reintenta; cualquier otra excepción sí, hasta 3 veces en el mismo hilo.
 */
@FunctionalInterface
public interface EnvelopeHandler {

    /**
     * Ejecuta el trabajo del mensaje.
     * NonRecoverableException termina en basicReject; cualquier otra excepción se reintenta.
     */
    void handle(MessageEnvelope envelope) throws Exception;
}
