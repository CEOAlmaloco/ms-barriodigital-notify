package cl.duoc.barriodigital.notify.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sin broker: los beans llevan los nombres, tipos y argumentos del contrato EP2-02.
 */
class RabbitTopologyConfigTest {

    private final RabbitTopologyConfig config = new RabbitTopologyConfig(contractNames());

    @Test
    void losTresExchangesSonDurablesYNoSeBorranSolos() {
        assertExchange(config.topicExchange(), "cmd.topic", ExchangeTypes.TOPIC);
        assertExchange(config.directExchange(), "cmd.direct", ExchangeTypes.DIRECT);
        assertExchange(config.deadExchange(), "cmd.dead.dlx", ExchangeTypes.DIRECT);
    }

    @Test
    void lasColasPrincipalesMuerenHaciaElExchangeDead() {
        assertMainQueue(config.emailQueue(), "q.cmd.email", "email.dead");
        assertMainQueue(config.crewQueue(), "q.cmd.crew", "crew.dead");
        assertMainQueue(config.certificateQueue(), "q.cmd.certificate", "certificate.dead");
    }

    @Test
    void lasDlqSonDurablesYSinArgumentos() {
        assertDlq(config.emailDlq(), "q.cmd.email.dlq");
        assertDlq(config.crewDlq(), "q.cmd.crew.dlq");
        assertDlq(config.certificateDlq(), "q.cmd.certificate.dlq");
    }

    @Test
    void cadaColaPrincipalTieneUnBindingDirectYUnoTopic() {
        assertBinding(config.emailDirectBinding(), "cmd.direct", "q.cmd.email", "email.send");
        assertBinding(config.emailTopicBinding(), "cmd.topic", "q.cmd.email", "email.*");
        assertBinding(config.crewDirectBinding(), "cmd.direct", "q.cmd.crew", "crew.ticket");
        assertBinding(config.crewTopicBinding(), "cmd.topic", "q.cmd.crew", "crew.#");
        assertBinding(config.certificateDirectBinding(), "cmd.direct", "q.cmd.certificate", "certificate.gen");
        assertBinding(config.certificateTopicBinding(), "cmd.topic", "q.cmd.certificate", "certificate.*");
    }

    @Test
    void cadaDlqRecibeDesdeElExchangeDead() {
        assertBinding(config.emailDeadBinding(), "cmd.dead.dlx", "q.cmd.email.dlq", "email.dead");
        assertBinding(config.crewDeadBinding(), "cmd.dead.dlx", "q.cmd.crew.dlq", "crew.dead");
        assertBinding(config.certificateDeadBinding(), "cmd.dead.dlx", "q.cmd.certificate.dlq", "certificate.dead");
    }

    /** Los valores de EP2-02, iguales a los de application.yml. */
    static RabbitNamesProperties contractNames() {
        return new RabbitNamesProperties(
                new RabbitNamesProperties.Exchange("cmd.topic", "cmd.direct", "cmd.dead.dlx"),
                new RabbitNamesProperties.Queue(
                        "q.cmd.email", "q.cmd.email.dlq",
                        "q.cmd.crew", "q.cmd.crew.dlq",
                        "q.cmd.certificate", "q.cmd.certificate.dlq"
                ),
                new RabbitNamesProperties.Routing(
                        "email.send", "email.*",
                        "crew.ticket", "crew.#",
                        "certificate.gen", "certificate.*",
                        "email.dead", "crew.dead", "certificate.dead"
                )
        );
    }

    private static void assertExchange(Exchange exchange, String name, String type) {
        assertEquals(name, exchange.getName());
        assertEquals(type, exchange.getType());
        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
    }

    private static void assertMainQueue(Queue queue, String name, String deadRoutingKey) {
        assertEquals(name, queue.getName());
        assertTrue(queue.isDurable());
        assertFalse(queue.isAutoDelete());
        assertFalse(queue.isExclusive());
        assertEquals(Map.of(
                "x-dead-letter-exchange", "cmd.dead.dlx",
                "x-dead-letter-routing-key", deadRoutingKey
        ), queue.getArguments());
    }

    private static void assertDlq(Queue queue, String name) {
        assertEquals(name, queue.getName());
        assertTrue(queue.isDurable());
        assertFalse(queue.isAutoDelete());
        assertFalse(queue.isExclusive());
        assertTrue(queue.getArguments().isEmpty());
    }

    private static void assertBinding(Binding binding, String exchange, String queue, String routingKey) {
        assertEquals(exchange, binding.getExchange());
        assertEquals(queue, binding.getDestination());
        assertEquals(Binding.DestinationType.QUEUE, binding.getDestinationType());
        assertEquals(routingKey, binding.getRoutingKey());
    }
}
