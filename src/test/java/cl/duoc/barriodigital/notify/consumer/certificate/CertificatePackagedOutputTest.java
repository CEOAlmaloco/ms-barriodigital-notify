package cl.duoc.barriodigital.notify.consumer.certificate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Lee los PDF copiados del contenedor: acredita recursos del JAR, no solo target/classes. */
@EnabledIfSystemProperty(named = "certificate.container-dir", matches = ".+")
class CertificatePackagedOutputTest {
    @Test
    void pdfDelJarEmpaquetadoIncluyeContenidoUtf8YFuentesIncrustadas() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("certificate.container-dir")))) {
            var paths = files.filter(path -> path.toString().endsWith(".pdf")).toList();
            assertThat(paths).hasSize(2);
            for (Path path : paths) {
                try (var pdf = Loader.loadPDF(path.toFile())) {
                    String text = new PDFTextStripper().getText(pdf);
                    assertThat(text).contains("caso-ficticio-ñ", "Atención pública", "Pasaje Ñandú 120", "UTC");
                    assertThat(text).contains(path.getFileName().toString().startsWith("certificate-receipt-")
                            ? CertificateDocument.Kind.RECEIPT.mainText() : CertificateDocument.Kind.RESOLVED.mainText());
                    for (var page : pdf.getPages()) {
                        for (var name : page.getResources().getFontNames()) {
                            assertThat(page.getResources().getFont(name).isEmbedded()).isTrue();
                            assertThat(page.getResources().getFont(name).getName()).contains("Inter");
                        }
                    }
                }
            }
        }
    }
}
