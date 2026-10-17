package cl.duoc.barriodigital.notify.messaging;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * eventId ya confirmados, por cola (contrato EP2-03). Clave cola|eventId: el mismo eventId en otra cola es otro mensaje.
 * Guarda hasta AckPolicy.IDEMPOTENCY_CAPACITY claves y expulsa la más antigua al pasarse.
 * Límite conocido: vive en memoria. Si notify se reinicia lo olvida y puede repetir un correo o un PDF,
 * y no se comparte entre instancias.
 */
@Component
public class ProcessedEvents {

    private final Map<String, Boolean> keys;

    public ProcessedEvents() {
        this(AckPolicy.IDEMPOTENCY_CAPACITY);
    }

    ProcessedEvents(int capacity) {
        this.keys = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized boolean contains(String queue, String eventId) {
        return keys.containsKey(key(queue, eventId));
    }

    /** Solo después de que el handler terminó bien. */
    public synchronized void markAcked(String queue, String eventId) {
        keys.put(key(queue, eventId), Boolean.TRUE);
    }

    synchronized int size() {
        return keys.size();
    }

    private static String key(String queue, String eventId) {
        return queue + "|" + eventId;
    }
}
