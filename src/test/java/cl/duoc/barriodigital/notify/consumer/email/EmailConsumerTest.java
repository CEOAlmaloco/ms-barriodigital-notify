package cl.duoc.barriodigital.notify.consumer.email;

import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.EnvelopeParser;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessor;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import cl.duoc.barriodigital.notify.messaging.ProcessedEvents;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Flujo real listener → parser/procesador → sender; el canal y el sender no usan red. */
class EmailConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Channel channel = mock(Channel.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final String queue = "q.test.email";
    private final ManualAckProcessor processor = new ManualAckProcessor(new EnvelopeParser(mapper), new ProcessedEvents());
    private final RabbitNamesProperties names = new RabbitNamesProperties(null,
            new RabbitNamesProperties.Queue(queue, "q.test.email.dlq", "crew", "crew.dlq", "pdf", "pdf.dlq"), null);
    private final EmailConsumer consumer = new EmailConsumer(processor, names, sender);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "email.admitted|BarrioDigital: trámite admitido|Tu trámite fue admitido para su gestión.",
            "email.visit|BarrioDigital: trámite en terreno|Tu trámite pasó a la etapa de atención en terreno.",
            "email.resolved|BarrioDigital: trámite resuelto|Tu trámite fue marcado como resuelto."
    })
    void componeLosTresTiposYTerminaElEnvioAntesDelAck(String type, String subject, String main) throws Exception {
        ObjectNode payload = payload().put("requestId", " caso-ñ ").put("procedureType", " bache ")
                .put("procedureLabel", " Atención pública ").put("address", " Pasaje Ñandú 123 ");
        ObjectNode original = payload.deepCopy();
        var sent = new EmailMessage(type, "Vecino@example.com", subject,
                main + "\n\nTrámite: Atención pública\nIdentificador: caso-ñ\nDirección: Pasaje Ñandú 123");
        boolean[] finished = {false};
        doAnswer(invocation -> { finished[0] = true; return null; }).when(sender).send(any());
        doAnswer(invocation -> { assertThat(finished[0]).isTrue(); return null; }).when(channel).basicAck(42L, false);

        consumer.consume(message(type, payload), channel);

        var order = inOrder(sender, channel);
        order.verify(sender).send(sent);
        order.verify(channel).basicAck(42L, false);
        verifyNoMoreInteractions(channel, sender);
        assertThat(payload).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"Etiqueta|tipo|Etiqueta", " |tipo|tipo", " | |Trámite vecinal"})
    void aplicaRespaldosYOmitelosDatosSinTexto(String label, String type, String expected) throws Exception {
        ObjectNode payload = payload().put("procedureLabel", label).put("procedureType", type)
                .putNull("requestId").put("address", "   ");

        consumer.consume(message("email.admitted", payload), channel);

        verify(sender).send(new EmailMessage("email.admitted", "Vecino@example.com", "BarrioDigital: trámite admitido",
                "Tu trámite fue admitido para su gestión.\n\nTrámite: " + expected));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void aceptaSoloElDestinatarioSinLosOpcionales() throws Exception {
        consumer.consume(message("email.admitted", payload()), channel);
        verify(sender).send(new EmailMessage("email.admitted", "Vecino@example.com", "BarrioDigital: trámite admitido",
                "Tu trámite fue admitido para su gestión.\n\nTrámite: Trámite vecinal"));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void aceptaTodosLosOpcionalesNulos() throws Exception {
        ObjectNode payload = payload().putNull("requestId").putNull("procedureType")
                .putNull("procedureLabel").putNull("address");
        consumer.consume(message("email.admitted", payload), channel);
        verify(sender).send(new EmailMessage("email.admitted", "Vecino@example.com", "BarrioDigital: trámite admitido",
                "Tu trámite fue admitido para su gestión.\n\nTrámite: Trámite vecinal"));
        verify(channel).basicAck(42L, false);
    }

    static Stream<String> invalidRecipients() {
        return Stream.of("{}", "{\"solicitanteEmail\":null}", "{\"solicitanteEmail\":\"   \"}",
                "{\"solicitanteEmail\":\"no-es-correo\"}", "{\"solicitanteEmail\":123}",
                "{\"solicitanteEmail\":[]}", "{\"solicitanteEmail\":{}}",
                "{\"solicitanteEmail\":\"a@example.com,b@example.com\"}",
                "{\"solicitanteEmail\":\"grupo:a@example.com;\"}",
                "{\"solicitanteEmail\":\"a@example.com\\r\\n\"}",
                "{\"solicitanteEmail\":\"a@example.com\\nb@example.com\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidRecipients")
    void rechazaDestinatarioInvalidoSinEnviar(String json) throws Exception {
        consumer.consume(message("email.admitted", (ObjectNode) mapper.readTree(json)), channel);
        verify(channel).basicReject(42L, false);
        verifyNoMoreInteractions(channel);
        verifyNoInteractions(sender);
    }

    static Stream<String> invalidOptionals() {
        return Stream.of("requestId", "procedureType", "procedureLabel", "address")
                .flatMap(field -> Stream.of("123", "[]", "{}", "true").map(value -> "{\"" + field + "\":" + value + "}"));
    }

    @ParameterizedTest
    @MethodSource("invalidOptionals")
    void rechazaOpcionalNoTextual(String json) throws Exception {
        ObjectNode payload = payload();
        payload.setAll((ObjectNode) mapper.readTree(json));
        consumer.consume(message("email.visit", payload), channel);
        verify(channel).basicReject(42L, false);
        verifyNoInteractions(sender);
    }

    @Test
    void rechazaTipoAjenoSinEnviar() throws Exception {
        consumer.consume(message("crew.ticket", payload()), channel);
        verify(channel).basicReject(42L, false);
        verifyNoInteractions(sender);
    }

    @Test
    void noRecuperableDelSenderSeRechazaUnaVezConIntentosCero() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ManualAckProcessor.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            doThrow(new NonRecoverableException("SMTP autenticacion rechazada")).when(sender).send(any());
            consumer.consume(message("email.visit", payload()), channel);
            verify(sender).send(any());
            verify(channel).basicReject(42L, false);
            verifyNoMoreInteractions(sender, channel);
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("mensaje-muerto cola=q.test.email eventId=evento-1 motivo=\"SMTP autenticacion rechazada\" intentos=0");
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void recuperableUsaLosTresIntentosDelProcesador() throws Exception {
        doThrow(new RecoverableException("SMTP temporal")).when(sender).send(any());
        consumer.consume(message("email.resolved", payload()), channel);
        verify(sender, times(3)).send(any());
        verify(channel).basicNack(42L, false, false);
        verifyNoMoreInteractions(sender, channel);
    }

    @Test
    void duplicadoSoloEnviaUnaVez() throws Exception {
        Message message = message("email.visit", payload());
        consumer.consume(message, channel);
        consumer.consume(message, channel);
        verify(sender).send(any());
        verify(channel, times(2)).basicAck(42L, false);
        verifyNoMoreInteractions(sender, channel);
    }

    private ObjectNode payload() {
        return mapper.createObjectNode().put("solicitanteEmail", " Vecino@example.com ");
    }

    private Message message(String type, ObjectNode payload) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("type", type).put("eventId", "evento-1");
        root.set("payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        return new Message(mapper.writeValueAsBytes(root), properties);
    }
}
