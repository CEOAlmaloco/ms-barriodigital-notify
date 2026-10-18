package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class CertificatePdfGeneratorTest {
    private final CertificatePdfGenerator generator = new CertificatePdfGenerator();

    static CertificateDocument document(CertificateDocument.Kind kind, String request, String address) {
        return new CertificateDocument(kind, "Evento-Ñ/AbC", request, "atencion", "Atención pública: ¿qué pasó?",
                address, Instant.parse("2026-10-16T15:10:00Z"), Instant.parse("2026-10-17T20:30:00Z"));
    }

    private byte[] generate(CertificateDocument document) throws IOException {
        var output = new ByteArrayOutputStream();
        generator.write(document, output);
        return output.toByteArray();
    }

    @ParameterizedTest
    @EnumSource(CertificateDocument.Kind.class)
    void pdfLegibleConTextoExactoFechasUtcYFuentesIncrustadas(CertificateDocument.Kind kind) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(generate(document(kind, "caso-ñ-123", "Pasaje Ñandú 120")))) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("BarrioDigital", kind.title(), kind.mainText(), kind.status(),
                    "caso-ñ-123", "Atención pública: ¿qué pasó?", "Pasaje Ñandú 120", "Evento-Ñ/AbC",
                    "Publicación del evento", "16/10/2026 15:10:00 UTC", "17/10/2026 20:30:00 UTC", "Página 1");
            assertThat(text).doesNotContain("Fecha de ingreso", "Fecha de resolución", "solicitanteEmail");
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(pdf.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(pdf.getDocumentCatalog().getLanguage()).isEqualTo("es-CL");
            var resources = pdf.getPage(0).getResources();
            int fonts = 0;
            for (var name : resources.getFontNames()) {
                assertThat(resources.getFont(name).isEmbedded()).isTrue();
                assertThat(resources.getFont(name).getName()).contains("Inter");
                fonts++;
            }
            assertThat(fonts).isEqualTo(2);
        }
    }

    @Test
    void omiteDireccionYPublicacionAusentes() throws Exception {
        var data = new CertificateDocument(CertificateDocument.Kind.RECEIPT, "e", "id", "bache", "Bache", "",
                null, Instant.parse("2026-10-17T20:30:00Z"));
        try (PDDocument pdf = Loader.loadPDF(generate(data))) {
            assertThat(new PDFTextStripper().getText(pdf)).doesNotContain("Dirección", "Publicación del evento")
                    .contains("Generación del documento", "17/10/2026 20:30:00 UTC");
        }
    }

    @Test
    void ajustaIdentificadoresSinPerdidaYPaginaContenidoLargoDentroDeMargenes() throws Exception {
        String request = "AbC123ñ".repeat(600);
        String address = "Primera línea\r\n" + "Atención en Pasaje Ñandú, número 120.\n".repeat(65) + "Última línea";
        try (PDDocument pdf = Loader.loadPDF(generate(document(CertificateDocument.Kind.RESOLVED, request, address)))) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            String text = new PDFTextStripper().getText(pdf);
            String body = text.replace("BarrioDigital", "").replace("Certificado de resolución", "")
                    .replaceAll("Página \\d+", "").replaceAll("\\s", "");
            assertThat(body.contains(request)).as("identificador completo entre páginas").isTrue();
            assertThat(body).contains("Primeralínea", "Últimalínea");
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(pdf)).contains("BarrioDigital", "Certificado de resolución", "Página " + page);
            }
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition position) {
                    assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(55);
                    assertThat(position.getXDirAdj() + position.getWidthDirAdj())
                            .isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 55);
                    assertThat(position.getYDirAdj()).isBetween(50f, PDRectangle.A4.getHeight() - 29);
                    super.processTextPosition(position);
                }
            }.getText(pdf);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "procedureType", "procedureLabel", "address", "eventId"})
    void glifoAusenteIdentificaCampoYCodigoSinVolcarDatos(String field) {
        String invalid = "dato-personal-" + new String(Character.toChars(0x10FFFF));
        var data = new CertificateDocument(CertificateDocument.Kind.RECEIPT,
                field.equals("eventId") ? invalid : "e", field.equals("requestId") ? invalid : "id",
                field.equals("procedureType") ? invalid : "tipo",
                field.equals("procedureLabel") || field.equals("procedureType") ? invalid : "Etiqueta",
                field.equals("address") ? invalid : "", null, Instant.EPOCH);
        assertThatThrownBy(() -> generate(data)).isInstanceOf(NonRecoverableException.class)
                .hasMessageContaining(field).hasMessageContaining("U+10FFFF").hasMessageNotContaining("dato-personal");
    }

    @Test
    void conservaComillasBarrasPuntuacionYSaltosComoDisposicion() throws Exception {
        String address = "¡Atención! \"A\" / norte\\sur\nSegunda línea\tÑuñoa";
        try (PDDocument pdf = Loader.loadPDF(generate(document(CertificateDocument.Kind.RECEIPT, "id", address)))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("¡Atención! \"A\" / norte\\sur", "Segunda línea", "Ñuñoa");
        }
    }

    @Test
    void coberturaSeCompruebaEnLosDatosMostrados() throws Exception {
        var data = new CertificateDocument(CertificateDocument.Kind.RECEIPT, "e", "id",
                new String(Character.toChars(0x10FFFF)), "Etiqueta recibida", "", null, Instant.EPOCH);
        try (var pdf = Loader.loadPDF(generate(data))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Etiqueta recibida");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursosAusentesOCorruptosSonDefectoDeInicio(boolean corrupt) throws Exception {
        byte[] bytecode;
        try (var input = CertificatePdfGenerator.class.getResourceAsStream("CertificatePdfGenerator.class")) {
            bytecode = input.readAllBytes();
        }
        class MissingFontsLoader extends ClassLoader {
            MissingFontsLoader() { super(CertificatePdfGenerator.class.getClassLoader()); }
            Class<?> isolated() { return defineClass(CertificatePdfGenerator.class.getName(), bytecode, 0, bytecode.length); }
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return corrupt ? new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}) : null;
            }
        }
        Class<?> isolated = new MissingFontsLoader().isolated();
        assertThatThrownBy(() -> isolated.getConstructor().newInstance())
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("recursos Inter");
    }
}
