package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;

import java.time.Instant;

public record CertificateDocument(Kind kind, String eventId, String requestId, String procedureType,
                                  String procedureLabel, String address, Instant publishedAt, Instant generatedAt) {

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

        Kind(String type, String filePart, String title, String mainText, String status) {
            this.type = type;
            this.filePart = filePart;
            this.title = title;
            this.mainText = mainText;
            this.status = status;
        }

        public String type() { return type; }
        public String filePart() { return filePart; }
        public String title() { return title; }
        public String mainText() { return mainText; }
        public String status() { return status; }

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
