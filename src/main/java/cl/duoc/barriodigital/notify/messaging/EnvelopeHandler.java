package cl.duoc.barriodigital.notify.messaging;

/**
 * Efecto de un consumidor sobre un sobre ya leído. Lanza NonRecoverableException si reintentar no sirve;
 * cualquier otra excepción se reintenta.
 */
@FunctionalInterface
public interface EnvelopeHandler {

    void handle(MessageEnvelope envelope) throws Exception;
}
