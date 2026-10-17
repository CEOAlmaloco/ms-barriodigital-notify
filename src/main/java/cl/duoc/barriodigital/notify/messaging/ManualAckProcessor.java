package cl.duoc.barriodigital.notify.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ACK manual común a los tres consumidores (contrato EP2-03). El listener le pasa su cola, el mensaje,
 * el canal y su handler; aquí se decide basicAck, basicNack o basicReject.
 * Los reintentos ocurren en el mismo hilo, sin devolver el mensaje al broker entre intentos.
 */
@Component
public class ManualAckProcessor {

    private static final Logger log = LoggerFactory.getLogger(ManualAckProcessor.class);

    static final String UNKNOWN_EVENT_ID = "desconocido";

    private final EnvelopeParser parser;
    private final ProcessedEvents processedEvents;
    private final Sleeper sleeper;

    @Autowired
    public ManualAckProcessor(EnvelopeParser parser, ProcessedEvents processedEvents) {
        this(parser, processedEvents, Sleeper.THREAD);
    }

    ManualAckProcessor(EnvelopeParser parser, ProcessedEvents processedEvents, Sleeper sleeper) {
        this.parser = parser;
        this.processedEvents = processedEvents;
        this.sleeper = sleeper;
    }

    public void process(String queue, Message message, Channel channel, EnvelopeHandler handler) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        MessageEnvelope envelope;
        try {
            envelope = parser.parse(message.getBody());
        } catch (InvalidEnvelopeException e) {
            String eventId = e.eventId() != null ? e.eventId() : UNKNOWN_EVENT_ID;
            reject(queue, eventId, e.getMessage(), 0, channel, deliveryTag);
            return;
        }
        String eventId = envelope.eventId();

        if (processedEvents.contains(queue, eventId)) {
            log.info("duplicado cola={} eventId={}", queue, eventId);
            ack(queue, eventId, channel, deliveryTag);
            return;
        }

        Exception lastFailure = null;
        for (int attempt = 1; attempt <= AckPolicy.MAX_ATTEMPTS; attempt++) {
            try {
                handler.handle(envelope);
                processedEvents.markAcked(queue, eventId);
                ack(queue, eventId, channel, deliveryTag);
                return;
            } catch (NonRecoverableException e) {
                // Rechazo directo: el contrato EP2-03 lo registra con intentos=0.
                reject(queue, eventId, reason(e), 0, channel, deliveryTag);
                return;
            } catch (Exception e) {
                lastFailure = e;
            }

            if (attempt < AckPolicy.MAX_ATTEMPTS) {
                try {
                    sleeper.sleep(AckPolicy.RETRY_DELAY);
                } catch (InterruptedException e) {
                    // Se restaura la bandera y el mensaje se da por agotado.
                    Thread.currentThread().interrupt();
                    nack(queue, eventId, "espera interrumpida", attempt, channel, deliveryTag);
                    return;
                }
            }
        }
        nack(queue, eventId, reason(lastFailure), AckPolicy.MAX_ATTEMPTS, channel, deliveryTag);
    }

    /** Mensaje de la excepción; si no es una de las dos del contrato, antepone su clase. */
    private static String reason(Exception e) {
        String name = e.getClass().getSimpleName();
        if (e.getMessage() == null) {
            return name;
        }
        boolean known = e instanceof RecoverableException || e instanceof NonRecoverableException;
        return known ? e.getMessage() : name + ": " + e.getMessage();
    }

    private void ack(String queue, String eventId, Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("fallo basicAck cola={} eventId={}", queue, eventId, e);
        }
    }

    /** Recuperable agotado: RabbitMQ lo manda a la DLQ por el dead-letter de la cola. */
    private void nack(String queue, String eventId, String reason, int attempts, Channel channel, long deliveryTag) {
        logDead(queue, eventId, reason, attempts);
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            log.error("fallo basicNack cola={} eventId={}", queue, eventId, e);
        }
    }

    /** No recuperable: a la DLQ sin reintentos. */
    private void reject(String queue, String eventId, String reason, int attempts, Channel channel, long deliveryTag) {
        logDead(queue, eventId, reason, attempts);
        try {
            channel.basicReject(deliveryTag, false);
        } catch (Exception e) {
            log.error("fallo basicReject cola={} eventId={}", queue, eventId, e);
        }
    }

    private static void logDead(String queue, String eventId, String reason, int attempts) {
        log.error("mensaje-muerto cola={} eventId={} motivo=\"{}\" intentos={}", queue, eventId, reason, attempts);
    }
}
