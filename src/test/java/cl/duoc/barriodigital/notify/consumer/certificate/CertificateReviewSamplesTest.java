package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.config.CertificateProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ejecución explícita para revisión visual. Los ejemplos nunca entran en el JAR de producción. */
@EnabledIfSystemProperty(named = "certificate.review-dir", matches = ".+")
class CertificateReviewSamplesTest {
    @Test
    void generaYRenderizaTodasLasPaginas() throws Exception {
        Path root = Path.of(System.getProperty("certificate.review-dir"));
        var generator = new CertificatePdfGenerator();
        var cases = List.of(
                CertificatePdfGeneratorTest.document(CertificateDocument.Kind.RECEIPT, "caso-demo-ingreso", "Los Aromos 120"),
                CertificatePdfGeneratorTest.document(CertificateDocument.Kind.RESOLVED, "caso-demo-resuelto", "Los Aromos 120"),
                new CertificateDocument(CertificateDocument.Kind.RECEIPT, "sin-metadatos", "caso-demo-sin-dirección", "bache", "Bache en calle", "", null, java.time.Instant.parse("2026-10-17T20:30:00Z")),
                CertificatePdfGeneratorTest.document(CertificateDocument.Kind.RECEIPT, "Ñuñoa-¿123?", "¡Atención pública! Pasaje Ñandú \"Norte\" / Sur\\Centro\nSegunda línea: áéíóú, ü y ñ."),
                CertificatePdfGeneratorTest.document(CertificateDocument.Kind.RESOLVED, "AbC123ñ".repeat(230), "Pasaje Ñandú 120"),
                CertificatePdfGeneratorTest.document(CertificateDocument.Kind.RESOLVED, "caso-demo-varias-páginas", "Atención en Pasaje Ñandú 120, sector norte.\n".repeat(68)));
        for (int index = 0; index < cases.size(); index++) {
            Path directory = root.resolve("ejemplo-" + (index + 1));
            Path file = new CertificateFileStore(new CertificateProperties(directory.toString()), generator).save(cases.get(index));
            try (var pdf = Loader.loadPDF(file.toFile())) {
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                    ImageIO.write(renderer.renderImageWithDPI(page, 100), "PNG", directory.resolve("pagina-" + (page + 1) + ".png").toFile());
                }
                Files.writeString(directory.resolve("paginas.txt"), Integer.toString(pdf.getNumberOfPages()));
            }
        }
    }
}
