package cl.duoc.barriodigital.notify.messaging;

import java.time.Duration;

/**
 * Valores de la política de confirmación (contrato EP2-03). Los nombres de cola no van aquí:
 * cada listener los lee de RabbitNamesProperties.
 */
public final class AckPolicy {

    /** Intentos en total, contando el primero. */
    public static final int MAX_ATTEMPTS = 3;

    /** Espera fija entre un intento y el siguiente. */
    public static final Duration RETRY_DELAY = Duration.ofMillis(1000);

    /** Claves cola|eventId que se recuerdan como confirmadas. */
    public static final int IDEMPOTENCY_CAPACITY = 10_000;

    private AckPolicy() {
    }
}
