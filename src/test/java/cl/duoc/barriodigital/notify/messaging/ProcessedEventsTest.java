package cl.duoc.barriodigital.notify.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotencia en memoria: clave cola|eventId y tope de AckPolicy.IDEMPOTENCY_CAPACITY.
 */
class ProcessedEventsTest {

    @Test
    void laClaveIncluyeLaCola() {
        ProcessedEvents events = new ProcessedEvents();

        events.markAcked("q.cmd.email", "e-1");

        assertThat(events.contains("q.cmd.email", "e-1")).isTrue();
        assertThat(events.contains("q.cmd.crew", "e-1")).isFalse();
    }

    @Test
    void alSuperarElTopeSeExpulsaLaMasAntigua() {
        ProcessedEvents events = new ProcessedEvents();

        for (int i = 0; i <= AckPolicy.IDEMPOTENCY_CAPACITY; i++) {
            events.markAcked("q.cmd.email", "e-" + i);
        }

        assertThat(events.size()).isEqualTo(AckPolicy.IDEMPOTENCY_CAPACITY);
        assertThat(events.contains("q.cmd.email", "e-0")).isFalse();
        assertThat(events.contains("q.cmd.email", "e-1")).isTrue();
        assertThat(events.contains("q.cmd.email", "e-" + AckPolicy.IDEMPOTENCY_CAPACITY)).isTrue();
    }
}
