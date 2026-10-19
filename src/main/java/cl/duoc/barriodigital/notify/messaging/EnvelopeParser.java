package cl.duoc.barriodigital.notify.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Lee el body del mensaje como sobre EP2-01. Si no es JSON, no es un objeto o faltan type, eventId o payload,
 * lanza InvalidEnvelopeException. No valida que el type sea uno de los seis: eso lo decide cada consumidor.
 */
@Component
public class EnvelopeParser {

    private final ObjectMapper objectMapper;

    public EnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MessageEnvelope parse(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new InvalidEnvelopeException("JSON invalido", null, e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEnvelopeException("JSON invalido", null);
        }

        String eventId = text(root, "eventId");
        String type = text(root, "type");
        if (eventId == null) {
            throw new InvalidEnvelopeException("sobre sin eventId", null);
        }
        if (type == null) {
            throw new InvalidEnvelopeException("sobre sin type", eventId);
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new InvalidEnvelopeException("sobre sin payload", eventId);
        }

        return new MessageEnvelope(
                type,
                eventId,
                timestamp(root, eventId),
                text(root, "traceId"),
                text(root, "correlationId"),
                payload
        );
    }

    /** Valor de texto no vacío, o null si falta, es null o no es texto. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    /** ISO-8601 con Z. Si falta queda null; si viene mal escrito el sobre no es válido. */
    private static Instant timestamp(JsonNode root, String eventId) {
        String value = text(root, "timestamp");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidEnvelopeException("timestamp invalido", eventId, e);
        }
    }
}
