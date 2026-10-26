package cl.duoc.barriodigital.notify.messaging;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Idempotencia solo en memoria: recuerda la clave cola|eventId ya confirmada para no repetir un correo o un PDF.
 * El mismo eventId en otra cola es otro mensaje. Si notify se reinicia, olvida las claves y no las comparte entre instancias.
 */
@Component
public class ProcessedEvents {

    private final Map<String, Boolean> keys;

    /** Memoria de producción: recuerda hasta 10.000 claves. */
    public ProcessedEvents() {
        this(AckPolicy.IDEMPOTENCY_CAPACITY);
    }

    /** Igual que producción, con un tope elegido. Los tests usan uno pequeño. */
    ProcessedEvents(int capacity) {
        this.keys = new LinkedHashMap<>(16, 0.75f, false) {
            /** Si ya hay más del tope, olvida la más antigua. */
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Dice si esa cola y ese eventId ya se procesaron. */
    public synchronized boolean contains(String queue, String eventId) {
        return keys.containsKey(key(queue, eventId));
    }

    /** Se llama solo después de que el handler terminó bien, antes del basicAck. */
    public synchronized void markAcked(String queue, String eventId) {
        keys.put(key(queue, eventId), Boolean.TRUE);
    }

    /** Cuántas claves hay ahora. Lo usan los tests. */
    synchronized int size() {
        return keys.size();
    }

    /** Arma la clave cola|eventId. El mismo eventId en otra cola es otro mensaje. */
    private static String key(String queue, String eventId) {
        return queue + "|" + eventId;
    }
}
