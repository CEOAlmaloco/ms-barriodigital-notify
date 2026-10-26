package cl.duoc.barriodigital.notify.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Convierte el cuerpo del mensaje en un objeto con type, eventId y payload.
 * Si el JSON está roto o faltan esos campos, lanza InvalidEnvelopeException. No revisa si el type es uno de los seis: eso lo hace cada consumidor.
 */
@Component
public class EnvelopeParser {

    private final ObjectMapper objectMapper;

    /** Recibe el conversor JSON. */
    public EnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convierte los bytes en un mensaje y exige un objeto JSON con type, eventId y payload objeto.
     * Si falta algo, el mensaje no es válido. El type lo valida cada consumidor.
     */
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
