package cl.duoc.barriodigital.notify.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Sin broker: el canal es un mock y la espera no duerme. Revisa la acción del contrato EP2-03 y el texto de los logs.
 */
class ManualAckProcessorTest {

    private static final String EMAIL = "q.cmd.email";
    private static final String CREW = "q.cmd.crew";
    private static final long TAG = 42L;
    private static final String EVENT_ID = "11aa22bb-33cc-44dd-55ee-66ff77889900";

    private final Channel channel = mock(Channel.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private ProcessedEvents processedEvents;
    private ManualAckProcessor processor;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        processedEvents = new ProcessedEvents();
        processor = new ManualAckProcessor(new EnvelopeParser(new ObjectMapper()), processedEvents, sleeps::add);
        logger = (Logger) LoggerFactory.getLogger(ManualAckProcessor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void mensajeValidoHaceAckUnaVez() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.admitted", EVENT_ID)), channel, envelope -> {
            assertThat(envelope.type()).isEqualTo("email.admitted");
            assertThat(envelope.payload().get("address").asText()).isEqualTo("Los Aromos 120");
            calls.incrementAndGet();
        });

        assertThat(calls).hasValue(1);
        verify(channel).basicAck(TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(sleeps).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void jsonInvalidoSeRechazaSinReintentos() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(CREW, message("{ no es json"), channel, envelope -> calls.incrementAndGet());

        assertThat(calls).hasValue(0);
        verify(channel).basicReject(TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(sleeps).isEmpty();
        assertOnlyLog(Level.ERROR,
                "mensaje-muerto cola=q.cmd.crew eventId=desconocido motivo=\"JSON invalido\" intentos=0");
    }

    @Test
    void cuerpoQueNoEsObjetoSeRechaza() throws Exception {
        processor.process(CREW, message("[1, 2, 3]"), channel, envelope -> { });

        verify(channel).basicReject(TAG, false);
        assertOnlyLog(Level.ERROR,
                "mensaje-muerto cola=q.cmd.crew eventId=desconocido motivo=\"JSON invalido\" intentos=0");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"eventId\":\"" + EVENT_ID + "\",\"payload\":{}}|" + EVENT_ID + "|sobre sin type",
            "{\"type\":\"email.admitted\",\"payload\":{}}|desconocido|sobre sin eventId",
            "{\"type\":\"email.admitted\",\"eventId\":\"" + EVENT_ID + "\"}|" + EVENT_ID + "|sobre sin payload"
    })
    void sobreIncompletoSeRechaza(String body, String loggedEventId, String reason) throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(body), channel, envelope -> calls.incrementAndGet());

        assertThat(calls).hasValue(0);
        verify(channel).basicReject(TAG, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.ERROR, "mensaje-muerto cola=q.cmd.email eventId=" + loggedEventId
                + " motivo=\"" + reason + "\" intentos=0");
    }

    @Test
    void noRecuperableDelHandlerSeRechazaConIntentosCero() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.admitted", EVENT_ID)), channel, envelope -> {
            calls.incrementAndGet();
            throw new NonRecoverableException("falta solicitanteEmail");
        });

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        verify(channel).basicReject(TAG, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.ERROR, "mensaje-muerto cola=q.cmd.email eventId=" + EVENT_ID
                + " motivo=\"falta solicitanteEmail\" intentos=0");
    }

    @Test
    void recuperableQueFallaSiempreTerminaEnNack() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.resolved", EVENT_ID)), channel, envelope -> {
            calls.incrementAndGet();
            throw new RecoverableException("SMTP no responde");
        });

        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        verify(channel).basicNack(TAG, false, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.ERROR, "mensaje-muerto cola=q.cmd.email eventId=" + EVENT_ID
                + " motivo=\"SMTP no responde\" intentos=3");
        assertThat(processedEvents.contains(EMAIL, EVENT_ID)).isFalse();
    }

    @Test
    void fallaDosVecesYFuncionaALaTercera() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.resolved", EVENT_ID)), channel, envelope -> {
            if (calls.incrementAndGet() < 3) {
                throw new RecoverableException("SMTP no responde");
            }
        });

        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoMoreInteractions(channel);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void excepcionInesperadaSeTrataComoRecuperable() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.visit", EVENT_ID)), channel, envelope -> {
            calls.incrementAndGet();
            throw new IllegalStateException("plantilla rota");
        });

        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
        verify(channel).basicNack(TAG, false, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.ERROR, "mensaje-muerto cola=q.cmd.email eventId=" + EVENT_ID
                + " motivo=\"IllegalStateException: plantilla rota\" intentos=3");
    }

    @Test
    void esperaInterrumpidaRestauraLaBanderaYHaceNack() throws Exception {
        ManualAckProcessor interrupted = new ManualAckProcessor(
                new EnvelopeParser(new ObjectMapper()), processedEvents, delay -> {
                    throw new InterruptedException();
                });
        AtomicInteger calls = new AtomicInteger();

        try {
            interrupted.process(EMAIL, message(envelope("email.visit", EVENT_ID)), channel, envelope -> {
                calls.incrementAndGet();
                throw new RecoverableException("SMTP no responde");
            });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(calls).hasValue(1);
        verify(channel).basicNack(TAG, false, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.ERROR, "mensaje-muerto cola=q.cmd.email eventId=" + EVENT_ID
                + " motivo=\"espera interrumpida\" intentos=1");
    }

    @Test
    void duplicadoHaceAckSinLlamarAlHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EnvelopeHandler handler = envelope -> calls.incrementAndGet();

        processor.process(EMAIL, message(envelope("email.visit", EVENT_ID)), channel, handler);
        processor.process(EMAIL, message(envelope("email.visit", EVENT_ID)), channel, handler);

        assertThat(calls).hasValue(1);
        verify(channel, times(2)).basicAck(TAG, false);
        verifyNoMoreInteractions(channel);
        assertOnlyLog(Level.INFO, "duplicado cola=q.cmd.email eventId=" + EVENT_ID);
    }

    @Test
    void mismoEventIdEnDosColasSeProcesaEnAmbas() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EnvelopeHandler handler = envelope -> calls.incrementAndGet();

        processor.process(EMAIL, message(envelope("email.visit", EVENT_ID)), channel, handler);
        processor.process(CREW, message(envelope("crew.ticket", EVENT_ID)), channel, handler);

        assertThat(calls).hasValue(2);
        verify(channel, times(2)).basicAck(TAG, false);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void mensajeQueFueALaDlqSeReprocesaSiVuelve() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process(EMAIL, message(envelope("email.resolved", EVENT_ID)), channel, envelope -> {
            calls.incrementAndGet();
            throw new RecoverableException("SMTP no responde");
        });
        processor.process(EMAIL, message(envelope("email.resolved", EVENT_ID)), channel,
                envelope -> calls.incrementAndGet());

        assertThat(calls).hasValue(4);
        verify(channel).basicNack(TAG, false, false);
        verify(channel).basicAck(TAG, false);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().startsWith("duplicado"));
    }

    @Test
    void siFallaElBasicAckNoSeRelanza() throws Exception {
        doThrow(new IOException("canal cerrado")).when(channel).basicAck(TAG, false);

        assertDoesNotThrow(() -> processor.process(EMAIL, message(envelope("email.admitted", EVENT_ID)),
                channel, envelope -> { }));

        verify(channel).basicAck(TAG, false);
        assertOnlyLog(Level.ERROR, "fallo basicAck cola=q.cmd.email eventId=" + EVENT_ID);
        assertThat(logs.list.get(0).getThrowableProxy().getMessage()).isEqualTo("canal cerrado");
    }

    @Test
    void siFallaElBasicRejectNoSeRelanza() throws Exception {
        doThrow(new IOException("canal cerrado")).when(channel).basicReject(TAG, false);

        assertDoesNotThrow(() -> processor.process(CREW, message("{ no es json"), channel, envelope -> { }));

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "mensaje-muerto cola=q.cmd.crew eventId=desconocido motivo=\"JSON invalido\" intentos=0",
                "fallo basicReject cola=q.cmd.crew eventId=desconocido");
    }

    private void assertOnlyLog(Level level, String text) {
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(level);
        assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo(text);
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    /** Sobre EP2-01 completo, como los ejemplos del contrato. */
    private static String envelope(String type, String eventId) {
        return """
                {
                  "type": "%s",
                  "eventId": "%s",
                  "timestamp": "2026-10-20T16:10:00Z",
                  "traceId": "aa11bb22-cc33-4dd5-8ee6-ff0011223344",
                  "correlationId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                  "payload": {
                    "requestId": "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                    "procedureType": "bache",
                    "procedureLabel": "Bache en calle",
                    "address": "Los Aromos 120",
                    "solicitanteEmail": "vecino@example.com"
                  }
                }
                """.formatted(type, eventId);
    }
}
