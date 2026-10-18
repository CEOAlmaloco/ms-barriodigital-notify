package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.config.CertificateProperties;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CertificateFileStoreTest {
    @TempDir Path temporary;
    private final CertificatePdfGenerator generator = new CertificatePdfGenerator();

    private CertificateDocument data(String eventId) {
        return new CertificateDocument(CertificateDocument.Kind.RECEIPT, eventId, "id", "bache", "Bache", "", null, Instant.EPOCH);
    }

    private CertificateFileStore store(Path path, CertificatePdfGenerator pdf) {
        return new CertificateFileStore(new CertificateProperties(path.toString()), pdf);
    }

    @Test
    void creaDirectorioYNombreSha256CompletoSinUsarDatosComoRuta() throws Exception {
        Path directory = temporary.resolve("nuevo/certificados");
        Path file = store(directory, generator).save(data("abc"));
        assertThat(file.getFileName().toString()).isEqualTo("certificate-receipt-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.pdf");
        assertThat(file.getParent()).isEqualTo(directory);
        try (var pdf = Loader.loadPDF(file.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
        Path unsafe = store(directory, generator).save(data(" ../Evento/Ñ\\AbC "));
        assertThat(unsafe.getParent()).isEqualTo(directory);
        assertThat(unsafe.getFileName().toString()).matches("certificate-receipt-[a-f0-9]{64}\\.pdf");
        assertThat(CertificateFileStore.fileName(data("abc"))).isNotEqualTo(CertificateFileStore.fileName(data("ABC")))
                .isNotEqualTo(CertificateFileStore.fileName(data(" abc ")));
        try (var files = Files.list(directory)) { assertThat(files).allMatch(p -> p.toString().endsWith(".pdf")); }
    }

    @Test
    void reemplazaArchivoExistenteEnLaMismaRuta() throws Exception {
        var store = store(temporary, generator);
        Path target = store.save(data("e"));
        byte[] previous = Files.readAllBytes(target);
        var next = new CertificateDocument(CertificateDocument.Kind.RECEIPT, "e", "nuevo", "bache", "Bache", "", null, Instant.now());
        assertThat(store.save(next)).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).isNotEqualTo(previous);
    }

    @Test
    void falloDeGeneracionLimpiaTemporalYConservaAnterior() throws Exception {
        Path target = store(temporary, generator).save(data("e"));
        byte[] previous = Files.readAllBytes(target);
        var failing = mock(CertificatePdfGenerator.class);
        IOException cause = new IOException("fallo controlado de escritura");
        doAnswer(call -> { call.<OutputStream>getArgument(1).write(new byte[]{1, 2, 3}); throw cause; })
                .when(failing).write(any(), any());
        assertThatThrownBy(() -> store(temporary, failing).save(data("e")))
                .isInstanceOf(RecoverableException.class).hasCause(cause);
        assertThat(Files.readAllBytes(target)).isEqualTo(previous);
        assertOnly(target);
    }

    @Test
    void publicacionAtomicaNoDisponibleNoHaceFallbackNiBorraAnterior() throws Exception {
        var store = spy(store(temporary, generator));
        Path target = store.save(data("e"));
        byte[] previous = Files.readAllBytes(target);
        var cause = new AtomicMoveNotSupportedException("temporal", "final", "fallo controlado");
        doThrow(cause).when(store).publish(any(), any());
        assertThatThrownBy(() -> store.save(data("e"))).isInstanceOf(RecoverableException.class).hasCause(cause);
        assertThat(Files.readAllBytes(target)).isEqualTo(previous);
        assertOnly(target);
    }

    @Test
    void falloSinArchivoAnteriorNoPublicaPdfParcial() throws Exception {
        var store = spy(store(temporary, generator));
        doThrow(new IOException("fallo de publicación")).when(store).publish(any(), any());
        assertThatThrownBy(() -> store.save(data("e"))).isInstanceOf(RecoverableException.class);
        try (var files = Files.list(temporary)) { assertThat(files).isEmpty(); }
    }

    @Test
    void glifoInvalidoNoSeReclasificaComoEscritura() throws Exception {
        var invalid = new CertificateDocument(CertificateDocument.Kind.RECEIPT, "e", "id", "bache", "Bache",
                new String(Character.toChars(0x10FFFF)), null, Instant.EPOCH);
        assertThatThrownBy(() -> store(temporary, generator).save(invalid)).isInstanceOf(NonRecoverableException.class);
        try (var files = Files.list(temporary)) { assertThat(files).isEmpty(); }
    }

    @Test
    void archivoEnLugarDelDirectorioEsRecuperable() throws Exception {
        Path file = Files.writeString(temporary.resolve("archivo"), "existente");
        assertThatThrownBy(() -> store(file, generator).save(data("e")))
                .isInstanceOf(RecoverableException.class).hasCauseInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("existente");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rechazaEnlacesEnDirectorioYDestino() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = Files.createSymbolicLink(temporary.resolve("link"), outside);
        assertThatThrownBy(() -> store(link.resolve("pdf"), generator).save(data("e"))).isInstanceOf(RecoverableException.class);
        Path directory = Files.createDirectory(temporary.resolve("pdf"));
        Path victim = Files.writeString(outside.resolve("victim"), "intacto");
        Files.createSymbolicLink(directory.resolve(CertificateFileStore.fileName(data("e"))), victim);
        assertThatThrownBy(() -> store(directory, generator).save(data("e"))).isInstanceOf(RecoverableException.class);
        assertThat(Files.readString(victim)).isEqualTo("intacto");
    }

    private void assertOnly(Path target) throws IOException {
        try (var files = Files.list(temporary)) { assertThat(files).containsExactly(target); }
    }

    @Test
    void falloDeLimpiezaSeSuprimeSinOcultarElOriginal() throws Exception {
        var failing = mock(CertificatePdfGenerator.class);
        IOException original = new IOException("escritura original");
        doAnswer(call -> {
            call.<OutputStream>getArgument(1).close();
            Path partial;
            try (var files = Files.list(temporary)) { partial = files.findFirst().orElseThrow(); }
            Files.delete(partial);
            Files.createDirectory(partial);
            Files.writeString(partial.resolve("bloquea-limpieza"), "ficticio");
            throw original;
        }).when(failing).write(any(), any());
        assertThatThrownBy(() -> store(temporary, failing).save(data("e")))
                .isInstanceOf(RecoverableException.class).hasCause(original);
        assertThat(original.getSuppressed()).hasSize(1);
        assertThat(original.getSuppressed()[0]).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
    }
}
