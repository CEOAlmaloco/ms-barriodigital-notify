package cl.duoc.barriodigital.notify.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología del contrato EP2-02. RabbitAdmin la declara al abrir la conexión.
 * notify también declara los exchanges para quedar healthy sin esperar a requests.
 * Declarar dos veces lo mismo no cambia nada; con otras propiedades RabbitMQ responde PRECONDITION_FAILED.
 * Los consumidores no declaran infraestructura.
 */
@Configuration
@EnableConfigurationProperties(RabbitNamesProperties.class)
public class RabbitTopologyConfig {

    private final RabbitNamesProperties names;

    public RabbitTopologyConfig(RabbitNamesProperties names) {
        this.names = names;
    }

    /** cmd.topic: por aquí publica requests los email.* y certificate.*. */
    @Bean
    TopicExchange topicExchange() {
        return ExchangeBuilder.topicExchange(names.exchange().topic()).durable(true).build();
    }

    /** cmd.direct: por aquí publica requests el crew.ticket. */
    @Bean
    DirectExchange directExchange() {
        return ExchangeBuilder.directExchange(names.exchange().direct()).durable(true).build();
    }

    /** cmd.dead.dlx: recibe lo que las colas principales dan por muerto. */
    @Bean
    DirectExchange deadExchange() {
        return ExchangeBuilder.directExchange(names.exchange().dead()).durable(true).build();
    }

    /** q.cmd.email: muere hacia cmd.dead.dlx con email.dead. */
    @Bean
    Queue emailQueue() {
        return mainQueue(names.queue().email(), names.routing().emailDead());
    }

    /** q.cmd.crew: muere hacia cmd.dead.dlx con crew.dead. */
    @Bean
    Queue crewQueue() {
        return mainQueue(names.queue().crew(), names.routing().crewDead());
    }

    /** q.cmd.certificate: muere hacia cmd.dead.dlx con certificate.dead. */
    @Bean
    Queue certificateQueue() {
        return mainQueue(names.queue().certificate(), names.routing().certificateDead());
    }

    /** q.cmd.email.dlq: sin consumidor ni dead-letter propio. */
    @Bean
    Queue emailDlq() {
        return QueueBuilder.durable(names.queue().emailDlq()).build();
    }

    /** q.cmd.crew.dlq: sin consumidor ni dead-letter propio. */
    @Bean
    Queue crewDlq() {
        return QueueBuilder.durable(names.queue().crewDlq()).build();
    }

    /** q.cmd.certificate.dlq: sin consumidor ni dead-letter propio. */
    @Bean
    Queue certificateDlq() {
        return QueueBuilder.durable(names.queue().certificateDlq()).build();
    }

    /** cmd.direct + email.send → q.cmd.email. */
    @Bean
    Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(directExchange()).with(names.routing().emailSend());
    }

    /** cmd.topic + email.* → q.cmd.email. */
    @Bean
    Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(topicExchange()).with(names.routing().emailTopic());
    }

    /** cmd.direct + crew.ticket → q.cmd.crew. */
    @Bean
    Binding crewDirectBinding() {
        return BindingBuilder.bind(crewQueue()).to(directExchange()).with(names.routing().crewTicket());
    }

    /** cmd.topic + crew.# → q.cmd.crew. */
    @Bean
    Binding crewTopicBinding() {
        return BindingBuilder.bind(crewQueue()).to(topicExchange()).with(names.routing().crewTopic());
    }

    /** cmd.direct + certificate.gen → q.cmd.certificate. */
    @Bean
    Binding certificateDirectBinding() {
        return BindingBuilder.bind(certificateQueue()).to(directExchange())
                .with(names.routing().certificateGen());
    }

    /** cmd.topic + certificate.* → q.cmd.certificate. */
    @Bean
    Binding certificateTopicBinding() {
        return BindingBuilder.bind(certificateQueue()).to(topicExchange())
                .with(names.routing().certificateTopic());
    }

    /** cmd.dead.dlx + email.dead → q.cmd.email.dlq. */
    @Bean
    Binding emailDeadBinding() {
        return BindingBuilder.bind(emailDlq()).to(deadExchange()).with(names.routing().emailDead());
    }

    /** cmd.dead.dlx + crew.dead → q.cmd.crew.dlq. */
    @Bean
    Binding crewDeadBinding() {
        return BindingBuilder.bind(crewDlq()).to(deadExchange()).with(names.routing().crewDead());
    }

    /** cmd.dead.dlx + certificate.dead → q.cmd.certificate.dlq. */
    @Bean
    Binding certificateDeadBinding() {
        return BindingBuilder.bind(certificateDlq()).to(deadExchange()).with(names.routing().certificateDead());
    }

    /** Cola clásica durable con su dead-letter. Sin x-queue-type: no es quorum. */
    private Queue mainQueue(String name, String deadRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(names.exchange().dead())
                .deadLetterRoutingKey(deadRoutingKey)
                .build();
    }
}
