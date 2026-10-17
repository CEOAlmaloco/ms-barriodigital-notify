package cl.duoc.barriodigital.notify.consumer.crew;

import cl.duoc.barriodigital.notify.messaging.MessageEnvelope;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrewTicketServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CrewTicketService service = new CrewTicketService(mapper);
    private final Logger logger = (Logger) LoggerFactory.getLogger(CrewTicketService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void registraTicketCompletoEnInfoSinCorreoNiSobre() throws Exception {
        service.handle(envelope("crew.ticket", "Evento-AbC", payload()));

        assertThat(ticket(0)).isEqualTo(mapper.readTree("""
                {"ticketNumber":"CREW-Evento-AbC","eventId":"Evento-AbC","requestId":"caso-1",
                 "procedureType":"bache","procedureLabel":"Bache en calle","address":"Los Aromos 120"}
                """));
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage()).doesNotContain("solicitanteEmail", "vecino@example.com",
                "correlationId", "traceId", "timestamp", "payload");
    }

    @Test
    void recortaTextosSinModificarElPayloadOriginal() throws Exception {
        ObjectNode payload = payload().put("requestId", " caso-ñ ").put("procedureType", " bache ")
                .put("procedureLabel", " Atención pública ").put("address", " Pasaje Ñandú 123 ");
        ObjectNode original = payload.deepCopy();

        service.handle(envelope("crew.ticket", "e-1", payload));

        JsonNode ticket = ticket(0);
        assertThat(ticket.get("requestId").textValue()).isEqualTo("caso-ñ");
        assertThat(ticket.get("procedureType").textValue()).isEqualTo("bache");
        assertThat(ticket.get("procedureLabel").textValue()).isEqualTo("Atención pública");
        assertThat(ticket.get("address").textValue()).isEqualTo("Pasaje Ñandú 123");
        assertThat(payload).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "null", "\"\"", "\"   \"", "\"\\t \\n\""})
    void usaProcedureTypeCuandoLaEtiquetaNoTieneTexto(String value) throws Exception {
        ObjectNode payload = payload().put("procedureType", " bache ");
        setField(payload, "procedureLabel", value);

        service.handle(envelope("crew.ticket", "e-1", payload));

        assertThat(ticket(0).get("procedureLabel").textValue()).isEqualTo("bache");
    }

    static Stream<Arguments> invalidRequiredFields() {
        return Stream.of("requestId", "procedureType", "address")
                .flatMap(field -> Stream.of("ausente", "null", "\"\"", "\"   \"", "\"\\t\\n\"",
                        "123", "true", "[]", "{}").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("invalidRequiredFields")
    void rechazaCadaObligatorioInvalidoSinRegistrarTicket(String field, String value) throws Exception {
        ObjectNode payload = payload();
        setField(payload, field, value);

        assertThatThrownBy(() -> service.handle(envelope("crew.ticket", "e-1", payload)))
                .isInstanceOf(NonRecoverableException.class)
                .hasMessageContaining("campo " + field)
                .hasMessageNotContaining("vecino@example.com")
                .hasMessageNotContaining("Los Aromos");
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "[]", "{}"})
    void rechazaEtiquetaNoTextualSinRegistrarTicket(String value) throws Exception {
        ObjectNode payload = payload();
        setField(payload, "procedureLabel", value);

        assertThatThrownBy(() -> service.handle(envelope("crew.ticket", "e-1", payload)))
                .isInstanceOf(NonRecoverableException.class)
                .hasMessage("campo procedureLabel debe ser textual");
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "null", "123", "true", "[]", "{}", "\"no-es-correo\""})
    void noUtilizaNiValidaSolicitanteEmail(String value) throws Exception {
        ObjectNode payload = payload();
        setField(payload, "solicitanteEmail", value);

        service.handle(envelope("crew.ticket", "e-1", payload));

        assertThat(logs.list).hasSize(1);
        assertThat(ticket(0).has("solicitanteEmail")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"crew.unknown", "email.visit", "certificate.receipt", "CREW.TICKET", " crew.ticket"})
    void rechazaTipoAjenoAntesDeLeerPayloadORegistrarTicket(String type) {
        assertThatThrownBy(() -> service.handle(envelope(type, "e-1", null)))
                .isInstanceOf(NonRecoverableException.class)
                .hasMessage("tipo de cuadrilla desconocido");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void conservaElEventIdCompletoYSuNumeroEntreReprocesamientos() throws Exception {
        String eventId = " Evento-No-UUID-AbC/ñ ";

        service.handle(envelope("crew.ticket", eventId, payload()));
        service.handle(envelope("crew.ticket", eventId, payload()));
        service.handle(envelope("crew.ticket", "otro-evento", payload()));

        assertThat(ticket(0).get("eventId").textValue()).isEqualTo(eventId);
        assertThat(ticket(0).get("ticketNumber").textValue()).isEqualTo("CREW-" + eventId);
        assertThat(ticket(1).get("ticketNumber")).isEqualTo(ticket(0).get("ticketNumber"));
        assertThat(ticket(2).get("ticketNumber").textValue()).isEqualTo("CREW-otro-evento");
        assertThat(ticket(2).get("requestId")).isEqualTo(ticket(0).get("requestId"));
    }

    @Test
    void escapaDatosEnUnaSolaLineaCompactaSinCambiarLaConfiguracionDelMapper() throws Exception {
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String special = "Atención \"A\" / norte\\sur\nsegunda\r\nlínea\tinterior\b\f\u0000fin";
        ObjectNode payload = payload().put("address", special).put("procedureLabel", special);
        String eventId = "AbC\"/\\\nñ";

        service.handle(envelope("crew.ticket", eventId, payload));

        assertThat(logs.list).hasSize(1);
        String message = logs.list.get(0).getFormattedMessage();
        assertThat(message).startsWith("crew-ticket {").doesNotContain("\n", "\r", "\t", "\b", "\f", "\u0000");
        assertThat(message).contains("Atención", "línea", "\\n", "\\r", "\\t", "\\\"", "\\\\");
        JsonNode ticket = ticket(0);
        assertThat(ticket.get("address").textValue()).isEqualTo(special);
        assertThat(ticket.get("procedureLabel").textValue()).isEqualTo(special);
        assertThat(ticket.get("eventId").textValue()).isEqualTo(eventId);
        assertThat(ticket.get("ticketNumber").textValue()).isEqualTo("CREW-" + eventId);
        assertThat(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
    }

    @Test
    void propagaFalloDeSerializacionConSuCausaSinRegistrarTicketParcial() {
        var module = new SimpleModule();
        module.addSerializer(CrewTicket.class, new JsonSerializer<>() {
            @Override
            public void serialize(CrewTicket ticket, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeStartObject();
                throw JsonMappingException.from(generator, "serializacion del ticket fallida",
                        new IOException("fallo controlado de serializacion"));
            }
        });
        mapper.registerModule(module);

        assertThatThrownBy(() -> service.handle(envelope("crew.ticket", "e-1", payload())))
                .isInstanceOf(JsonProcessingException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("fallo controlado de serializacion");
        assertThat(logs.list).isEmpty();
    }

    private JsonNode ticket(int index) throws JsonProcessingException {
        String message = logs.list.get(index).getFormattedMessage();
        assertThat(message).startsWith("crew-ticket ");
        return mapper.readTree(message.substring("crew-ticket ".length()));
    }

    private void setField(ObjectNode payload, String field, String value) throws JsonProcessingException {
        if ("ausente".equals(value)) {
            payload.remove(field);
        } else {
            payload.set(field, mapper.readTree(value));
        }
    }

    private ObjectNode payload() {
        return mapper.createObjectNode().put("requestId", "caso-1").put("procedureType", "bache")
                .put("procedureLabel", "Bache en calle").put("address", "Los Aromos 120")
                .put("solicitanteEmail", "vecino@example.com");
    }

    private MessageEnvelope envelope(String type, String eventId, JsonNode payload) {
        return new MessageEnvelope(type, eventId, null, "trace-1", "correlation-1", payload);
    }
}
