package cl.duoc.barriodigital.notify.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ACK manual común a los tres consumidores.
 * En el mismo hilo decide basicAck si hubo éxito, basicNack con requeue=false si el fallo recuperable se agotó, y basicReject con requeue=false si no es recuperable.
 */
@Component
public class ManualAckProcessor {

    private static final Logger log = LoggerFactory.getLogger(ManualAckProcessor.class);

    static final String UNKNOWN_EVENT_ID = "desconocido";

    private final EnvelopeParser parser;
    private final ProcessedEvents processedEvents;
    private final Sleeper sleeper;

    /** Constructor de producción. La espera entre los 3 intentos duerme el mismo hilo. */
    @Autowired
    public ManualAckProcessor(EnvelopeParser parser, ProcessedEvents processedEvents) {
        this(parser, processedEvents, Sleeper.THREAD);
    }

    /** Constructor que permite cambiar la espera. Los tests pasan una que no duerme. */
    ManualAckProcessor(EnvelopeParser parser, ProcessedEvents processedEvents, Sleeper sleeper) {
        this.parser = parser;
        this.processedEvents = processedEvents;
        this.sleeper = sleeper;
    }

    /**
     * Decide el ACK en el mismo hilo, con hasta 3 intentos.
     * El éxito o un duplicado hace basicAck; lo no recuperable hace basicReject con requeue=false; el recuperable agotado hace basicNack con requeue=false y escribe el log mensaje-muerto.
     */
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

    /** Mensaje de la excepción. Si no es RecoverableException ni NonRecoverableException, antepone el nombre de la clase. */
    private static String reason(Exception e) {
        String name = e.getClass().getSimpleName();
        if (e.getMessage() == null) {
            return name;
        }
        boolean known = e instanceof RecoverableException || e instanceof NonRecoverableException;
        return known ? e.getMessage() : name + ": " + e.getMessage();
    }

    /** Éxito: basicAck para que RabbitMQ saque el mensaje de la cola. */
    private void ack(String queue, String eventId, Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("fallo basicAck cola={} eventId={}", queue, eventId, e);
        }
    }

    /** Recuperable agotado: basicNack con requeue=false. La cola lo manda a la DLQ, que no tiene consumidor. */
    private void nack(String queue, String eventId, String reason, int attempts, Channel channel, long deliveryTag) {
        logDead(queue, eventId, reason, attempts);
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            log.error("fallo basicNack cola={} eventId={}", queue, eventId, e);
        }
    }

    /** No recuperable: basicReject con requeue=false, sin reintentos. La cola lo manda a la DLQ. */
    private void reject(String queue, String eventId, String reason, int attempts, Channel channel, long deliveryTag) {
        logDead(queue, eventId, reason, attempts);
        try {
            channel.basicReject(deliveryTag, false);
        } catch (Exception e) {
            log.error("fallo basicReject cola={} eventId={}", queue, eventId, e);
        }
    }

    /** Escribe el log mensaje-muerto con cola, eventId, motivo e intentos. */
    private static void logDead(String queue, String eventId, String reason, int attempts) {
        log.error("mensaje-muerto cola={} eventId={} motivo=\"{}\" intentos={}", queue, eventId, reason, attempts);
    }
}
