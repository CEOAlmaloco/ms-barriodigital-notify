package cl.duoc.barriodigital.notify.messaging;

import java.time.Duration;

/**
 * Espera entre los intentos del ACK manual, en el mismo hilo.
 * Producción usa THREAD con Thread.sleep; los tests pueden pasar una espera que no duerme.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = delay -> Thread.sleep(delay.toMillis());

    /** Espera el tiempo pedido. THREAD usa Thread.sleep. */
    void sleep(Duration delay) throws InterruptedException;
}
