package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.config.CertificateProperties;
import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CertificateConsumerTest {
    @TempDir Path directory;
    private static final String QUEUE = "q.test.certificate.configurada";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Channel channel = mock(Channel.class);
    private final Sleeper sleeper = mock(Sleeper.class);
    private final ProcessedEvents events = new ProcessedEvents();
    private final CertificatePdfGenerator generator = spy(new CertificatePdfGenerator());
    private final Logger logger = (Logger) LoggerFactory.getLogger(CertificateService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private CertificateFileStore store;
    private CertificateConsumer consumer;
    private final RabbitNamesProperties names = new RabbitNamesProperties(null,
            new RabbitNamesProperties.Queue("email", "email.dlq", "crew", "crew.dlq", QUEUE, QUEUE + ".dlq"), null);

    @BeforeEach
    void setUp() {
        store = spy(new CertificateFileStore(new CertificateProperties(directory.toString()), generator));
        consumer = consumer(events, Instant.parse("2026-10-17T20:30:00Z"));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private CertificateConsumer consumer(ProcessedEvents processed, Instant generated) {
        var processor = ManualAckProcessorTestSupport.withSleeper(new EnvelopeParser(mapper), processed, sleeper);
        return new CertificateConsumer(processor, names, new CertificateService(store, Clock.fixed(generated, ZoneOffset.UTC), mapper));
    }

    private Message message(String type, String event, ObjectNode payload, long tag) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("type", type).put("eventId", event);
        root.set("payload", payload);
        return raw(mapper.writeValueAsString(root), tag);
    }

    private Message raw(String body, long tag) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private ObjectNode payload() {
        return mapper.createObjectNode().put("requestId", "caso-ñ").put("procedureType", "bache");
    }

    @ParameterizedTest
    @ValueSource(strings = {"certificate.receipt", "certificate.resolved"})
    void archivoLegibleYLogSeguroAntesDelAck(String type) throws Exception {
        String event = "Evento\"/Ñ\\A\nB";
        doAnswer(call -> {
            Path file;
            try (var files = Files.list(directory)) { file = files.findFirst().orElseThrow(); }
            try (var pdf = Loader.loadPDF(file.toFile())) {
                assertThat(new PDFTextStripper().getText(pdf)).contains(CertificateDocument.Kind.fromType(type).mainText());
            }
            assertThat(logs.list).hasSize(1);
            String entry = logs.list.getFirst().getFormattedMessage();
            assertThat(entry).startsWith("certificate-pdf ").doesNotContain("\n", "caso-ñ", "payload");
            var json = mapper.readTree(entry.substring("certificate-pdf ".length()));
            assertThat(json.get("eventId").textValue()).isEqualTo(event);
            assertThat(json.get("fileName").textValue()).isEqualTo(file.getFileName().toString());
            return null;
        }).when(channel).basicAck(42L, false);
        consumer.consume(message(type, event, payload(), 42), channel);
        verify(channel).basicAck(42, false);
        verifyNoMoreInteractions(channel);
        assertThat(events.contains(QUEUE, event)).isTrue();
        assertThat(events.contains("q.cmd.certificate", event)).isFalse();
    }

    @Test
    void duplicadoNoRegenera() throws Exception {
        consumer.consume(message("certificate.receipt", "e", payload(), 42), channel);
        Path file;
        try (var files = Files.list(directory)) { file = files.findFirst().orElseThrow(); }
        byte[] original = Files.readAllBytes(file);
        consumer.consume(message("certificate.receipt", "e", payload(), 43), channel);
        verify(generator).write(any(), any());
        verify(channel).basicAck(42, false);
        verify(channel).basicAck(43, false);
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void reinicioReprocesaEnLaMismaRutaConNuevaFecha() throws Exception {
        consumer.consume(message("certificate.receipt", "e", payload(), 42), channel);
        CertificateConsumer restarted = consumer(new ProcessedEvents(), Instant.parse("2026-10-18T20:30:00Z"));
        restarted.consume(message("certificate.receipt", "e", payload(), 43), channel);
        verify(generator, times(2)).write(any(), any());
        try (var files = Files.list(directory)) {
            var paths = files.toList();
            assertThat(paths).hasSize(1);
            try (var pdf = Loader.loadPDF(paths.getFirst().toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains("18/10/2026");
                verify(channel).basicAck(43, false);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{no-json", "[]", "{\"eventId\":\"e\",\"type\":\"certificate.receipt\",\"payload\":[]}"})
    void sobreInvalidoNoGenera(String body) throws Exception {
        consumer.consume(raw(body, 42), channel);
        verify(channel).basicReject(42, false);
        verifyNoInteractions(generator, sleeper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"certificate.gen", "certificate.unknown", "email.resolved"})
    void tipoAjenoNoGenera(String type) throws Exception {
        consumer.consume(message(type, "e", payload(), 42), channel);
        verify(channel).basicReject(42, false);
        verifyNoInteractions(generator, sleeper);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void payloadInvalidoNoSeMarcaProcesado() throws Exception {
        consumer.consume(message("certificate.receipt", "e", mapper.createObjectNode(), 42), channel);
        verify(channel).basicReject(42, false);
        verifyNoInteractions(generator, sleeper);
        assertThat(events.contains(QUEUE, "e")).isFalse();
    }

    @Test
    void falloDePublicacionAgotaTresIntentosSinFinalNiAck() throws Exception {
        doThrow(new IOException("fallo de publicación controlado")).when(store).publish(any(), any());
        consumer.consume(message("certificate.receipt", "e", payload(), 42), channel);
        verify(generator, times(3)).write(any(), any());
        verify(sleeper, times(2)).sleep(AckPolicy.RETRY_DELAY);
        verify(channel).basicNack(42, false, false);
        verifyNoMoreInteractions(channel);
        assertThat(events.contains(QUEUE, "e")).isFalse();
        assertThat(logs.list).isEmpty();
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
    }
}
