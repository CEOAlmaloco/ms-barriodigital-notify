package cl.duoc.barriodigital.notify.messaging;

import java.time.Duration;

/**
 * Espera entre intentos. Producción usa THREAD; los tests pasan una que no duerme.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = delay -> Thread.sleep(delay.toMillis());

    void sleep(Duration delay) throws InterruptedException;
}
