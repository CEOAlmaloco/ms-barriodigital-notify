package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;

import java.time.Instant;

/**
 * Datos que van impresos en el PDF.
 * Distingue el comprobante de ingreso del certificado de resolución.
 */
public record CertificateDocument(Kind kind, String eventId, String requestId, String procedureType,
                                  String procedureLabel, String address, Instant publishedAt, Instant generatedAt) {

    /** Los dos documentos posibles. Cada uno trae su type, su nombre de archivo y sus textos. */
    public enum Kind {
        RECEIPT("certificate.receipt", "receipt", "Comprobante de ingreso",
                "Tu trámite fue registrado en BarrioDigital con estado INGRESADO.", "INGRESADO"),
        RESOLVED("certificate.resolved", "resolved", "Certificado de resolución",
                "Tu trámite fue marcado como RESUELTO en BarrioDigital.", "RESUELTO");

        private final String type;
        private final String filePart;
        private final String title;
        private final String mainText;
        private final String status;

        /** Guarda el type del evento, la parte del nombre de archivo, el título, el texto y el estado. */
        Kind(String type, String filePart, String title, String mainText, String status) {
            this.type = type;
            this.filePart = filePart;
            this.title = title;
            this.mainText = mainText;
            this.status = status;
        }

        /** Type del evento, por ejemplo certificate.resolved. */
        public String type() { return type; }
        /** Trozo que va en el nombre del archivo: receipt o resolved. */
        public String filePart() { return filePart; }
        /** Título que se ve en el PDF. */
        public String title() { return title; }
        /** Párrafo principal del PDF. */
        public String mainText() { return mainText; }
        /** Estado que se imprime, INGRESADO o RESUELTO. */
        public String status() { return status; }

        /** Busca el kind por el type. Si no es uno de los dos, el mensaje no se reintenta. */
        public static Kind fromType(String type) {
            for (Kind kind : values()) {
                if (kind.type.equals(type)) {
                    return kind;
                }
            }
            throw new NonRecoverableException("tipo de certificado desconocido");
        }
    }
}
