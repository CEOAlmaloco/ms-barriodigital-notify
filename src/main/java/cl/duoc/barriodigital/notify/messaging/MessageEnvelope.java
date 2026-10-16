package cl.duoc.barriodigital.notify.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Sobre común de los seis eventos (contrato EP2-01). payload queda como JsonNode: cada consumidor lee sus campos.
 * timestamp, traceId y correlationId pueden venir null; type, eventId y payload siempre vienen.
 */
public record MessageEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        JsonNode payload
) {
}
