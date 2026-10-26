package cl.duoc.barriodigital.notify.consumer.email;

import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessor;
import cl.duoc.barriodigital.notify.messaging.MessageEnvelope;
import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Escucha la cola de los correos.
 * No confirma el mensaje: se lo pasa a ManualAckProcessor, que decide basicAck, reintento o descarte.
 */
@Component
public class EmailConsumer {

    private final ManualAckProcessor processor;
    private final RabbitNamesProperties names;
    private final EmailSender sender;

    /** Recibe el procesador común, los nombres de las colas y quién manda el correo. */
    public EmailConsumer(ManualAckProcessor processor, RabbitNamesProperties names, EmailSender sender) {
        this.processor = processor;
        this.names = names;
        this.sender = sender;
    }

    /** RabbitMQ llama esto por cada mensaje de q.cmd.email y entrega el trabajo a process. */
    @RabbitListener(queues = "${barriodigital.rabbit.queue.email}")
    public void consume(Message message, Channel channel) {
        processor.process(names.queue().email(), message, channel, this::handle);
    }

    /**
     * Arma el correo según el tipo.
     * email.admitted, email.visit y email.resolved tienen asunto y frase propios. Otro tipo, o un correo inválido, no se reintenta.
     */
    private void handle(MessageEnvelope envelope) {
        String subject;
        String main;
        switch (envelope.type()) {
            case "email.admitted" -> {
                subject = "BarrioDigital: trámite admitido";
                main = "Tu trámite fue admitido para su gestión.";
            }
            case "email.visit" -> {
                subject = "BarrioDigital: trámite en terreno";
                main = "Tu trámite pasó a la etapa de atención en terreno.";
            }
            case "email.resolved" -> {
                subject = "BarrioDigital: trámite resuelto";
                main = "Tu trámite fue marcado como resuelto.";
            }
            default -> throw new NonRecoverableException("tipo de email desconocido");
        }

        JsonNode payload = envelope.payload();
        JsonNode recipient = payload.get("solicitanteEmail");
        if (recipient == null || !recipient.isTextual() || !EmailAddress.isValid(recipient.textValue())) {
            throw new NonRecoverableException("solicitanteEmail debe ser una direccion unica valida");
        }
        String requestId = optionalText(payload, "requestId");
        String procedureType = optionalText(payload, "procedureType");
        String procedureLabel = optionalText(payload, "procedureLabel");
        String address = optionalText(payload, "address");
        String label = !procedureLabel.isEmpty() ? procedureLabel
                : !procedureType.isEmpty() ? procedureType : "Trámite vecinal";
        StringBuilder body = new StringBuilder(main).append("\n\nTrámite: ").append(label);
        if (!requestId.isEmpty()) {
            body.append("\nIdentificador: ").append(requestId);
        }
        if (!address.isEmpty()) {
            body.append("\nDirección: ").append(address);
        }
        sender.send(new EmailMessage(envelope.type(), recipient.textValue().trim(), subject, body.toString()));
    }

    /**
     * Lee un campo de texto opcional.
     * Si no viene, devuelve vacío. Si viene y no es texto, el mensaje no se puede reintentar.
     */
    private static String optionalText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            throw new NonRecoverableException("campo " + field + " debe ser textual");
        }
        return node.textValue().trim();
    }
}
