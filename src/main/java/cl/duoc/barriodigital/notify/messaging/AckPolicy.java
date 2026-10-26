package cl.duoc.barriodigital.notify.messaging;

import java.time.Duration;

/**
 * Límites fijos del ACK manual: 3 intentos en el mismo hilo, 1 segundo entre ellos y hasta 10.000 claves en memoria.
 * Los nombres de cola no van aquí: cada listener los lee de RabbitNamesProperties.
 */
public final class AckPolicy {

    /** Intentos en total en el mismo hilo, contando el primero. */
    public static final int MAX_ATTEMPTS = 3;

    /** Espera fija entre un intento y el siguiente, en el mismo hilo. */
    public static final Duration RETRY_DELAY = Duration.ofMillis(1000);

    /** Tope de claves cola|eventId recordadas en memoria como ya confirmadas. */
    public static final int IDEMPOTENCY_CAPACITY = 10_000;

    /** Nadie instancia esta clase: solo expone los tres límites del ACK manual. */
    private AckPolicy() {
    }
}
