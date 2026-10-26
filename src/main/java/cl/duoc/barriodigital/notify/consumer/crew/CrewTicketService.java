package cl.duoc.barriodigital.notify.consumer.crew;

import cl.duoc.barriodigital.notify.messaging.MessageEnvelope;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Arma el ticket de la cuadrilla y lo deja en el log.
 * No llama a una persona: registra el número, el trámite, el tipo y la dirección.
 */
@Service
public class CrewTicketService {

    private static final Logger log = LoggerFactory.getLogger(CrewTicketService.class);

    private final ObjectMapper objectMapper;

    /** Recibe el conversor que deja el ticket en una sola línea JSON. */
    public CrewTicketService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Solo acepta crew.ticket y exige requestId, procedureType y address; si la etiqueta viene vacía, usa el tipo.
     * El número del ticket es CREW- más el eventId.
     */
    public void handle(MessageEnvelope envelope) throws JsonProcessingException {
        if (!"crew.ticket".equals(envelope.type())) {
            throw new NonRecoverableException("tipo de cuadrilla desconocido");
        }

        JsonNode payload = envelope.payload();
        String requestId = requiredText(payload, "requestId");
        String procedureType = requiredText(payload, "procedureType");
        String address = requiredText(payload, "address");
        String procedureLabel = optionalText(payload, "procedureLabel");
        if (procedureLabel.isBlank()) {
            procedureLabel = procedureType;
        }

        var ticket = new CrewTicket("CREW-" + envelope.eventId(), envelope.eventId(), requestId,
                procedureType, procedureLabel, address);
        String json = objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(ticket);
        log.info("crew-ticket {}", json);
    }

    /** Lee un texto obligatorio. Si falta o está en blanco, el mensaje no se reintenta. */
    private static String requiredText(JsonNode payload, String field) {
        String value = optionalText(payload, field);
        if (value.isBlank()) {
            throw new NonRecoverableException("campo " + field + " es obligatorio y no debe estar vacio");
        }
        return value;
    }

    /** Lee un texto opcional. Si no viene, devuelve vacío. Si no es texto, no se reintenta. */
    private static String optionalText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            throw new NonRecoverableException("campo " + field + " debe ser textual");
        }
        return node.textValue().trim();
    }
}
