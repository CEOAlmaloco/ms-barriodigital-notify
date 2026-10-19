package cl.duoc.barriodigital.notify.messaging;

/** Permite probar otros consumidores con una espera simulada sin ampliar la API de producción. */
public final class ManualAckProcessorTestSupport {

    private ManualAckProcessorTestSupport() {
    }

    public static ManualAckProcessor withSleeper(EnvelopeParser parser, ProcessedEvents events, Sleeper sleeper) {
        return new ManualAckProcessor(parser, events, sleeper);
    }
}
