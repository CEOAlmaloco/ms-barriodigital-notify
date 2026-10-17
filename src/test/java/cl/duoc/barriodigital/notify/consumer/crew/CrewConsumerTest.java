package cl.duoc.barriodigital.notify.consumer.crew;

import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.AckPolicy;
import cl.duoc.barriodigital.notify.messaging.EnvelopeParser;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessor;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessorTestSupport;
import cl.duoc.barriodigital.notify.messaging.ProcessedEvents;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import cl.duoc.barriodigital.notify.messaging.Sleeper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Flujo real listener → parser/procesador → servicio/log, sin broker ni esperas reales. */
class CrewConsumerTest {

    private static final String QUEUE = "q.test.crew.configurada";
    private static final String EVENT_ID = "Evento-No-UUID-AbC";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Channel channel = mock(Channel.class);
    private final Sleeper sleeper = mock(Sleeper.class);
    private final ProcessedEvents events = new ProcessedEvents();
    private final ManualAckProcessor processor = ManualAckProcessorTestSupport.withSleeper(
            new EnvelopeParser(mapper), events, sleeper);
    private final CrewTicketService service = spy(new CrewTicketService(mapper));
    private final RabbitNamesProperties names = new RabbitNamesProperties(null,
            new RabbitNamesProperties.Queue("email", "email.dlq", QUEUE, QUEUE + ".dlq", "pdf", "pdf.dlq"), null);
    private final CrewConsumer consumer = new CrewConsumer(processor, names, service);
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(CrewTicketService.class);
    private final Logger processorLogger = (Logger) LoggerFactory.getLogger(ManualAckProcessor.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        serviceLogger.addAppender(logs);
        processorLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        processorLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void registraElTicketAntesDelAckYUsaLaColaConfigurada() throws Exception {
        doAnswer(invocation -> {
            assertThat(logs.list).hasSize(1);
            String entry = logs.list.get(0).getFormattedMessage();
            assertThat(entry).startsWith("crew-ticket ");
            JsonNode ticket = mapper.readTree(entry.substring("crew-ticket ".length()));
            assertThat(ticket.get("ticketNumber").textValue()).isEqualTo("CREW-" + EVENT_ID);
            assertThat(ticket.get("address").textValue()).isEqualTo("Los Aromos 120");
            return null;
        }).when(channel).basicAck(42L, false);

        consumer.consume(message("crew.ticket", payload(), 42L), channel);

        verify(service).handle(any());
        verify(channel).basicAck(42L, false);
        verifyNoMoreInteractions(service, channel);
        verifyNoInteractions(sleeper);
        assertThat(events.contains(QUEUE, EVENT_ID)).isTrue();
        assertThat(events.contains("q.cmd.crew", EVENT_ID)).isFalse();
    }

    @Test
    void dosEntregasDelEventoProducenUnTicketYDosAck() throws Exception {
        consumer.consume(message("crew.ticket", payload(), 42L), channel);
        consumer.consume(message("crew.ticket", payload(), 43L), channel);

        verify(service).handle(any());
        verify(channel).basicAck(42L, false);
        verify(channel).basicAck(43L, false);
        verifyNoMoreInteractions(service, channel);
        verifyNoInteractions(sleeper);
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("crew-ticket ");
        assertThat(logs.list.get(1).getFormattedMessage())
                .isEqualTo("duplicado cola=" + QUEUE + " eventId=" + EVENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "procedureType", "address"})
    void rechazaObligatorioAusenteSinTicketNiReintentos(String field) throws Exception {
        ObjectNode payload = payload();
        payload.remove(field);

        consumer.consume(message("crew.ticket", payload, 42L), channel);

        assertRejectedOnce("campo " + field);
    }

    @Test
    void correlationIdNoReemplazaRequestId() throws Exception {
        ObjectNode root = mapper.createObjectNode().put("type", "crew.ticket").put("eventId", EVENT_ID)
                .put("correlationId", "caso-alternativo");
        ObjectNode payload = payload();
        payload.remove("requestId");
        root.set("payload", payload);

        consumer.consume(rawMessage(mapper.writeValueAsString(root)), channel);

        assertRejectedOnce("campo requestId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"crew.unknown", "email.visit"})
    void rechazaTipoAjenoSinTicketNiReintentos(String type) throws Exception {
        consumer.consume(message(type, payload(), 42L), channel);

        assertRejectedOnce("tipo de cuadrilla desconocido");
    }

    @Test
    void rechazaEtiquetaNoTextualSinTicketNiReintentos() throws Exception {
        consumer.consume(message("crew.ticket", payload().put("procedureLabel", true), 42L), channel);

        assertRejectedOnce("campo procedureLabel debe ser textual");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ no es json", "[]",
            "{\"type\":\"crew.ticket\",\"eventId\":\"e-1\",\"payload\":[]}"})
    void sobreInvalidoPasaPorElProcesadorSinInvocarServicio(String body) throws Exception {
        consumer.consume(rawMessage(body), channel);

        verify(channel).basicReject(42L, false);
        verifyNoMoreInteractions(channel);
        verifyNoInteractions(service, sleeper);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("mensaje-muerto cola=" + QUEUE)
                .endsWith("intentos=0");
    }

    static Stream<RuntimeException> controlledFailures() {
        return Stream.of(new RecoverableException("fallo controlado del servicio en prueba"),
                new IllegalStateException("fallo inesperado controlado en prueba"));
    }

    @ParameterizedTest
    @MethodSource("controlledFailures")
    void falloControladoDelServicioAgotaTresIntentosSinEsperaReal(RuntimeException failure) throws Exception {
        // Simula el fallo del handler; no representa una operación de red o disco del logger.
        doThrow(failure).when(service).handle(any());

        consumer.consume(message("crew.ticket", payload(), 42L), channel);

        verify(service, times(3)).handle(any());
        verify(sleeper, times(2)).sleep(AckPolicy.RETRY_DELAY);
        verify(channel).basicNack(42L, false, false);
        verifyNoMoreInteractions(service, sleeper, channel);
        assertThat(events.contains(QUEUE, EVENT_ID)).isFalse();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("mensaje-muerto cola=" + QUEUE)
                .contains("eventId=" + EVENT_ID).endsWith("intentos=3");
    }

    private void assertRejectedOnce(String reason) throws Exception {
        verify(service).handle(any());
        verify(channel).basicReject(42L, false);
        verifyNoMoreInteractions(service, channel);
        verifyNoInteractions(sleeper);
        assertThat(events.contains(QUEUE, EVENT_ID)).isFalse();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("mensaje-muerto cola=" + QUEUE)
                .contains(reason).endsWith("intentos=0");
    }

    private ObjectNode payload() {
        return mapper.createObjectNode().put("requestId", "caso-1").put("procedureType", "bache")
                .put("address", "Los Aromos 120");
    }

    private Message message(String type, JsonNode payload, long tag) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("type", type).put("eventId", EVENT_ID);
        root.set("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message(mapper.writeValueAsBytes(root), properties);
    }

    private Message rawMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
