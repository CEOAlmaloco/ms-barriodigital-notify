package cl.duoc.barriodigital.notify.consumer.crew;

/** Ticket registrado en el log; no representa una asignación efectiva a una cuadrilla. */
public record CrewTicket(
        String ticketNumber,
        String eventId,
        String requestId,
        String procedureType,
        String procedureLabel,
        String address
) {
}
