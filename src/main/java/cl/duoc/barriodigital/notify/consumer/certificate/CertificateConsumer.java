package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.config.RabbitNamesProperties;
import cl.duoc.barriodigital.notify.messaging.ManualAckProcessor;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CertificateConsumer {
    private final ManualAckProcessor processor;
    private final RabbitNamesProperties names;
    private final CertificateService service;

    public CertificateConsumer(ManualAckProcessor processor, RabbitNamesProperties names, CertificateService service) {
        this.processor = processor;
        this.names = names;
        this.service = service;
    }

    @RabbitListener(queues = "${barriodigital.rabbit.queue.certificate}")
    public void consume(Message message, Channel channel) {
        processor.process(names.queue().certificate(), message, channel, service::handle);
    }
}
