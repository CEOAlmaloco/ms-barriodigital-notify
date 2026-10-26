package cl.duoc.barriodigital.notify.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Mensaje ya leído de los seis eventos.
 * type, eventId y payload siempre vienen; timestamp, traceId y correlationId pueden ser null. Cada consumidor lee de payload lo que necesita.
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
