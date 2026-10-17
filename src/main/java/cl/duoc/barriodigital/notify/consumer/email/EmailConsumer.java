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

@Component
public class EmailConsumer {

    private final ManualAckProcessor processor;
    private final RabbitNamesProperties names;
    private final EmailSender sender;

    public EmailConsumer(ManualAckProcessor processor, RabbitNamesProperties names, EmailSender sender) {
        this.processor = processor;
        this.names = names;
        this.sender = sender;
    }

    @RabbitListener(queues = "${barriodigital.rabbit.queue.email}")
    public void consume(Message message, Channel channel) {
        processor.process(names.queue().email(), message, channel, this::handle);
    }

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
