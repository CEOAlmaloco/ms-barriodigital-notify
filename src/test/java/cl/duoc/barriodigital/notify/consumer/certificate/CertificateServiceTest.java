package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.MessageEnvelope;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CertificateServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CertificateFileStore store = mock(CertificateFileStore.class);
    private final Instant generated = Instant.parse("2026-10-17T20:30:00Z");
    private final CertificateService service = new CertificateService(store, Clock.fixed(generated, ZoneOffset.UTC), mapper);

    private ObjectNode payload() {
        return mapper.createObjectNode().put("requestId", " caso-ñ ").put("procedureType", " bache ");
    }

    private MessageEnvelope envelope(String type, ObjectNode payload) {
        return new MessageEnvelope(type, "Evento-AbC/ñ", null, null, "no-es-respaldo", payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"certificate.receipt", "certificate.resolved"})
    void componeConRespaldoDireccionOpcionalYRelojFijoSinMutarPayload(String type) throws Exception {
        ObjectNode payload = payload().putNull("procedureLabel").put("address", "  ")
                .set("solicitanteEmail", mapper.createObjectNode());
        ObjectNode original = payload.deepCopy();
        when(store.save(any())).thenReturn(Path.of("certificate.pdf"));

        service.handle(envelope(type, payload));

        var captor = ArgumentCaptor.forClass(CertificateDocument.class);
        verify(store).save(captor.capture());
        CertificateDocument document = captor.getValue();
        assertThat(document.requestId()).isEqualTo("caso-ñ");
        assertThat(document.procedureType()).isEqualTo("bache");
        assertThat(document.procedureLabel()).isEqualTo("bache");
        assertThat(document.address()).isEmpty();
        assertThat(document.eventId()).isEqualTo("Evento-AbC/ñ");
        assertThat(document.publishedAt()).isNull();
        assertThat(document.generatedAt()).isEqualTo(generated);
        assertThat(payload).isEqualTo(original);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of("requestId", "procedureType", "procedureLabel", "address").flatMap(field ->
                Stream.of("123", "true", "[]", "{}").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void rechazaTiposJsonIncorrectos(String field, String json) throws Exception {
        ObjectNode payload = payload();
        payload.set(field, mapper.readTree(json));
        assertThatThrownBy(() -> service.handle(envelope("certificate.receipt", payload)))
                .isInstanceOf(NonRecoverableException.class).hasMessageContaining(field);
        verifyNoInteractions(store);
    }

    static Stream<Arguments> missingRequired() {
        return Stream.of("requestId", "procedureType").flatMap(field ->
                Stream.of("missing", "null", "\"\"", "\" \\t \\n\"").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("missingRequired")
    void obligatoriosNoUsanCorrelationId(String field, String value) throws Exception {
        ObjectNode payload = payload();
        if (value.equals("missing")) { payload.remove(field); }
        else { payload.set(field, mapper.readTree(value)); }
        assertThatThrownBy(() -> service.handle(envelope("certificate.receipt", payload)))
                .isInstanceOf(NonRecoverableException.class).hasMessageContaining(field);
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"certificate.gen", "certificate.unknown", "email.resolved", "CERTIFICATE.RECEIPT", " certificate.receipt"})
    void rechazaTipoAntesDeLeerPayload(String type) {
        assertThatThrownBy(() -> service.handle(envelope(type, null))).isInstanceOf(NonRecoverableException.class);
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"\"", "\"   \"", "\"Etiqueta ñ\""})
    void opcionalesAdmitenAusenciaNulosYTexto(String value) throws Exception {
        ObjectNode payload = payload();
        if (!value.equals("missing")) {
            payload.set("procedureLabel", mapper.readTree(value));
            payload.set("address", mapper.readTree(value));
        }
        when(store.save(any())).thenReturn(Path.of("certificate.pdf"));
        service.handle(envelope("certificate.receipt", payload));
        var captor = ArgumentCaptor.forClass(CertificateDocument.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().procedureLabel()).isEqualTo(value.contains("Etiqueta") ? "Etiqueta ñ" : "bache");
        assertThat(captor.getValue().address()).isEqualTo(value.contains("Etiqueta") ? "Etiqueta ñ" : "");
    }

    @Test
    void conservaTimestampDePublicacion() throws Exception {
        when(store.save(any())).thenReturn(Path.of("certificate.pdf"));
        Instant published = Instant.parse("2026-10-16T15:10:00Z");
        service.handle(new MessageEnvelope("certificate.resolved", "e", published, null, null, payload()));
        var captor = ArgumentCaptor.forClass(CertificateDocument.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().publishedAt()).isEqualTo(published);
    }
}
