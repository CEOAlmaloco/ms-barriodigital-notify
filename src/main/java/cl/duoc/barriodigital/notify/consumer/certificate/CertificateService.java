package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.MessageEnvelope;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

@Service
public class CertificateService {
    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);
    private final CertificateFileStore store;
    private final Clock clock;
    private final ObjectMapper mapper;

    public CertificateService(CertificateFileStore store, Clock clock, ObjectMapper mapper) {
        this.store = store;
        this.clock = clock;
        this.mapper = mapper;
    }

    public void handle(MessageEnvelope envelope) throws Exception {
        var kind = CertificateDocument.Kind.fromType(envelope.type());
        JsonNode payload = envelope.payload();
        String requestId = requiredText(payload, "requestId");
        String procedureType = requiredText(payload, "procedureType");
        String label = optionalText(payload, "procedureLabel");
        String address = optionalText(payload, "address");
        var document = new CertificateDocument(kind, envelope.eventId(), requestId, procedureType,
                label.isBlank() ? procedureType : label, address, envelope.timestamp(), clock.instant());
        Path file = store.save(document);
        // Serialización JSON compacta: no permite que los identificadores inyecten líneas en el log.
        String entry = mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                Map.of("type", kind.type(), "eventId", envelope.eventId(), "fileName", file.getFileName().toString()));
        log.info("certificate-pdf {}", entry);
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = optionalText(payload, field);
        if (value.isBlank()) {
            throw new NonRecoverableException("campo " + field + " es obligatorio y no debe estar vacío");
        }
        return value;
    }

    private static String optionalText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            throw new NonRecoverableException("campo " + field + " debe ser textual");
        }
        return node.textValue().strip();
    }
}
