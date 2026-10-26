package cl.duoc.barriodigital.notify.consumer.crew;

/**
 * Ficha del ticket: número, evento, trámite, tipo, etiqueta y dirección.
 * Que exista el registro no significa que una cuadrilla ya haya salido.
 */
public record CrewTicket(
        String ticketNumber,
        String eventId,
        String requestId,
        String procedureType,
        String procedureLabel,
        String address
) {
}
