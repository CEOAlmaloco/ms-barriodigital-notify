package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dibuja el PDF con la fuente Inter embebida, para que las tildes y la eñe se vean igual en cualquier computador.
 * No busca fuentes en internet ni en el sistema.
 */
@Component
public class CertificatePdfGenerator {
    private static final Color PRIMARY = Color.decode("#814B8B");
    private static final Color TEXT = Color.decode("#252329");
    private static final Color SECONDARY = Color.decode("#68636B");
    private static final Color BORDER = Color.decode("#DDD9DF");
    private static final float MARGIN = 20 * 72 / 25.4f;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float BODY_SIZE = 11;
    private static final float LEADING = 16.5f;
    private static final float LABEL_SIZE = 9.5f;
    private static final float METADATA_SIZE = 9;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final byte[] regular;
    private final byte[] semibold;

    /**
     * Carga las fuentes Inter y prueba que puedan escribir tildes y eñes.
     * Si faltan, la aplicación no arranca, para no consumir un mensaje y descubrirlo tarde.
     */
    public CertificatePdfGenerator() {
        try {
            regular = resource("/fonts/inter/Inter-Regular.ttf");
            semibold = resource("/fonts/inter/Inter-SemiBold.ttf");
            // Comprueba recursos y cobertura española al iniciar, antes de consumir mensajes.
            try (PDDocument pdf = new PDDocument()) {
                font(pdf, regular).encode("BarrioDigital áéíóúüñ ÁÉÍÓÚÜÑ ¿¡");
                font(pdf, semibold).encode("Comprobante de ingreso Certificado de resolución áéíóúüñ");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("recursos Inter de certificados ausentes, corruptos o incompatibles", e);
        }
    }

    /** Lee una fuente embebida. Si no está en el jar, falla. */
    private static byte[] resource(String name) throws IOException {
        try (InputStream input = CertificatePdfGenerator.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("recurso de fuente ausente: " + name);
            }
            return input.readAllBytes();
        }
    }

    /** Carga la fuente dentro del PDF para poder medir y dibujar texto. */
    private static PDType0Font font(PDDocument pdf, byte[] bytes) throws IOException {
        return PDType0Font.load(pdf, new ByteArrayInputStream(bytes), true);
    }

    /**
     * Dibuja el A4 y lo escribe en el stream.
     * Si un carácter no se puede dibujar, el mensaje no se reintenta: la fuente no va a incorporarlo.
     */
    public void write(CertificateDocument document, OutputStream output) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font body = font(pdf, regular);
            PDType0Font heading = font(pdf, semibold);
            validate(body, "requestId", document.requestId());
            validate(body, document.procedureLabel().equals(document.procedureType()) ? "procedureType" : "procedureLabel",
                    document.procedureLabel());
            validate(body, "address", document.address());
            validate(body, "eventId", document.eventId());
            PDDocumentInformation information = pdf.getDocumentInformation();
            information.setTitle(document.kind().title());
            information.setAuthor("BarrioDigital");
            information.setSubject(document.kind().type());
            pdf.getDocumentCatalog().setLanguage("es-CL");
            try (Layout layout = new Layout(pdf, body, heading, document.kind().title())) {
                layout.paragraph(document.kind().mainText(), body, BODY_SIZE, LEADING, TEXT);
                layout.gap(24);
                layout.field("Identificador del trámite", document.requestId(), false);
                layout.field("Tipo de trámite", document.procedureLabel(), false);
                if (!document.address().isEmpty()) {
                    layout.field("Dirección", document.address(), false);
                }
                layout.field("Estado correspondiente al evento", document.kind().status(), false);
                layout.metadataSeparator();
                layout.field("Identificador del evento", document.eventId(), true);
                if (document.publishedAt() != null) {
                    layout.field("Publicación del evento", DATE.format(document.publishedAt()), true);
                }
                layout.field("Generación del documento", DATE.format(document.generatedAt()), true);
            }
            pdf.save(output);
        }
    }

    /** Recorre el texto y rechaza el carácter que la fuente no tiene, salvo salto y tabulación. */
    private static void validate(PDType0Font font, String field, String value) throws IOException {
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            if (point == '\n' || point == '\r' || point == '\t') {
                continue;
            }
            try {
                font.encode(new String(Character.toChars(point)));
            } catch (IllegalArgumentException e) {
                // No conserva como causa el mensaje de PDFBox, que puede incluir el carácter recibido.
                throw new NonRecoverableException("campo " + field + " contiene carácter no representable "
                        + String.format(Locale.ROOT, "U+%04X", point));
            }
        }
    }

    /** Parte un párrafo en líneas que caben en el ancho de la hoja. */
    private static List<String> lines(String text, PDType0Font font, float size) throws IOException {
        List<String> result = new ArrayList<>();
        // Los blancos de control son disposición, no glifos. Se conservan los párrafos vacíos.
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            float width = 0;
            int lastSpace = -1;
            for (int offset = 0; offset < paragraph.length();) {
                int point = paragraph.codePointAt(offset);
                String character = new String(Character.toChars(point));
                float advance = font.getStringWidth(character) * size / 1000;
                while (width + advance > WIDTH && !line.isEmpty()) {
                    if (lastSpace >= 0) {
                        result.add(line.substring(0, lastSpace + 1));
                        line = new StringBuilder(line.substring(lastSpace + 1));
                    } else {
                        result.add(line.toString());
                        line.setLength(0);
                    }
                    width = font.getStringWidth(line.toString()) * size / 1000;
                    lastSpace = line.lastIndexOf(" ");
                }
                line.append(character);
                width += advance;
                if (point == ' ') {
                    lastSpace = line.length() - 1;
                }
                offset += Character.charCount(point);
            }
            result.add(line.toString());
        }
        return result;
    }

    /** Cursor vertical de la página. Abre otra hoja cuando el texto no cabe. */
    private static final class Layout implements AutoCloseable {
        private final PDDocument pdf;
        private final PDType0Font body;
        private final PDType0Font heading;
        private final String title;
        private PDPageContentStream stream;
        private float y;

        /** Abre la primera página. */
        private Layout(PDDocument pdf, PDType0Font body, PDType0Font heading, String title) throws IOException {
            this.pdf = pdf;
            this.body = body;
            this.heading = heading;
            this.title = title;
            page();
        }

        /** Cierra la página anterior, abre otra y pinta encabezado, título y "Página N". */
        private void page() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            boolean first = pdf.getNumberOfPages() == 1;
            y = PDRectangle.A4.getHeight() - MARGIN;
            text("BarrioDigital", heading, first ? 18 : 12, PRIMARY, MARGIN, y);
            y -= first ? 38 : 24;
            text(title, heading, first ? 24 : 13, TEXT, MARGIN, y);
            y -= 18;
            rule(PRIMARY, first ? 1.5f : 0.75f);
            y -= first ? 28 : 24;
            String footer = "Página " + pdf.getNumberOfPages();
            float footerWidth = body.getStringWidth(footer) * METADATA_SIZE / 1000;
            text(footer, body, METADATA_SIZE, SECONDARY, PDRectangle.A4.getWidth() - MARGIN - footerWidth, 30);
        }

        /** Dibuja un texto en una posición. */
        private void text(String value, PDType0Font font, float size, Color color, float x, float baseline) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(value);
            stream.endText();
        }

        /** Dibuja una línea horizontal del color y grosor pedidos. */
        private void rule(Color color, float width) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(width);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        /** Si no queda espacio, abre otra página. */
        private void ensure(float height) throws IOException {
            if (y - height < MARGIN) {
                page();
            }
        }

        /** Escribe un párrafo línea por línea y baja el cursor. */
        private void paragraph(String value, PDType0Font font, float size, float leading, Color color) throws IOException {
            for (String line : lines(value, font, size)) {
                ensure(leading);
                text(line, font, size, color, MARGIN, y);
                y -= leading;
            }
        }

        /** Pinta la etiqueta y debajo el valor. */
        private void field(String label, String value, boolean metadata) throws IOException {
            float size = metadata ? METADATA_SIZE : BODY_SIZE;
            float leading = metadata ? 14 : LEADING;
            ensure(14 + leading);
            text(label, heading, LABEL_SIZE, SECONDARY, MARGIN, y);
            y -= 14;
            paragraph(value, body, size, leading, TEXT);
            gap(metadata ? 10 : 16);
        }

        /** Deja espacio y dibuja la línea que separa los datos del trámite de los del evento. */
        private void metadataSeparator() throws IOException {
            ensure(80);
            gap(8);
            rule(BORDER, 0.5f);
            gap(24);
        }

        /** Baja el cursor sin escribir. */
        private void gap(float height) {
            y -= height;
        }

        /** Cierra el dibujo de la página al terminar el PDF. */
        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
