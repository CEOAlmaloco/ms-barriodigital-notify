package cl.duoc.barriodigital.notify.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El sobre EP2-01 se lee completo; si no es válido sale InvalidEnvelopeException con el eventId cuando se pudo leer.
 */
class EnvelopeParserTest {

    private final EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());

    @Test
    void leeTodosLosCamposDelSobre() {
        MessageEnvelope envelope = parser.parse(bytes("""
                {"type":"crew.ticket","eventId":"e-1","timestamp":"2026-10-21T13:00:00Z",
                 "traceId":"t-1","correlationId":"c-1","payload":{"address":"Los Aromos 120"}}
                """));

        assertThat(envelope.type()).isEqualTo("crew.ticket");
        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.timestamp()).isEqualTo(Instant.parse("2026-10-21T13:00:00Z"));
        assertThat(envelope.traceId()).isEqualTo("t-1");
        assertThat(envelope.correlationId()).isEqualTo("c-1");
        assertThat(envelope.payload().get("address").asText()).isEqualTo("Los Aromos 120");
    }

    @Test
    void losCamposOpcionalesPuedenFaltar() {
        MessageEnvelope envelope = parser.parse(bytes("""
                {"type":"crew.ticket","eventId":"e-1","payload":{}}
                """));

        assertThat(envelope.timestamp()).isNull();
        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.correlationId()).isNull();
    }

    @Test
    void timestampMalEscritoGuardaElEventId() {
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"type":"crew.ticket","eventId":"e-1","timestamp":"ayer","payload":{}}
                """)))
                .isInstanceOf(InvalidEnvelopeException.class)
                .hasMessage("timestamp invalido")
                .extracting(e -> ((InvalidEnvelopeException) e).eventId())
                .isEqualTo("e-1");
    }

    @Test
    void payloadQueNoEsObjetoNoEsValido() {
        assertThatThrownBy(() -> parser.parse(bytes("""
                {"type":"crew.ticket","eventId":"e-1","payload":"Los Aromos 120"}
                """)))
                .isInstanceOf(InvalidEnvelopeException.class)
                .hasMessage("sobre sin payload");
    }

    @Test
    void cuerpoVacioEsJsonInvalido() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(InvalidEnvelopeException.class)
                .hasMessage("JSON invalido");
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
