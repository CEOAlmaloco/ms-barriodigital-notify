package cl.duoc.barriodigital.notify.consumer.crew;

import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessor;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Escucha la cola de la cuadrilla.
 * Cuando llega el aviso de visita en terreno, se lo pasa a quien registra el ticket. El ACK manual lo decide ManualAckProcessor.
 */
@Component
public class CrewConsumer {

    private final ManualAckProcessor processor;
    private final RabbitNamesProperties names;
    private final CrewTicketService ticketService;

    /** Recibe el procesador común, los nombres de las colas y el servicio del ticket. */
    public CrewConsumer(ManualAckProcessor processor, RabbitNamesProperties names, CrewTicketService ticketService) {
        this.processor = processor;
        this.names = names;
        this.ticketService = ticketService;
    }

    /** RabbitMQ llama esto por cada mensaje de q.cmd.crew. */
    @RabbitListener(queues = "${barriodigital.rabbit.queue.crew}")
    public void consume(Message message, Channel channel) {
        processor.process(names.queue().crew(), message, channel, ticketService::handle);
    }
}
