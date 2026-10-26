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
 * Declara exchanges, colas clásicas durables (no replicadas) y bindings al abrir la conexión, también para quedar healthy sin esperar a requests.
 * Un direct enruta por igualdad y un topic por patrón (* una palabra, # cero o más). La DLQ no tiene consumidor.
 */
@Configuration
@EnableConfigurationProperties(RabbitNamesProperties.class)
public class RabbitTopologyConfig {

    private final RabbitNamesProperties names;

    /** Recibe los nombres leídos del yml para crear exchanges, colas y bindings. */
    public RabbitTopologyConfig(RabbitNamesProperties names) {
        this.names = names;
    }

    /** Exchange topic. Enruta por patrón: * es una palabra y # es cero o más. Por aquí publica requests los email.* y certificate.*. */
    @Bean
    TopicExchange topicExchange() {
        return ExchangeBuilder.topicExchange(names.exchange().topic()).durable(true).build();
    }

    /** Exchange direct. Enruta por igualdad exacta de routing key. Por aquí publica requests el crew.ticket. */
    @Bean
    DirectExchange directExchange() {
        return ExchangeBuilder.directExchange(names.exchange().direct()).durable(true).build();
    }

    /** Exchange direct de mensajes muertos. Recibe por igualdad de routing key lo que las colas principales descartan. */
    @Bean
    DirectExchange deadExchange() {
        return ExchangeBuilder.directExchange(names.exchange().dead()).durable(true).build();
    }

    /** Cola clásica q.cmd.email, no replicada. Si el mensaje muere, va a cmd.dead.dlx con email.dead. */
    @Bean
    Queue emailQueue() {
        return mainQueue(names.queue().email(), names.routing().emailDead());
    }

    /** Cola clásica q.cmd.crew, no replicada. Si el mensaje muere, va a cmd.dead.dlx con crew.dead. */
    @Bean
    Queue crewQueue() {
        return mainQueue(names.queue().crew(), names.routing().crewDead());
    }

    /** Cola clásica q.cmd.certificate, no replicada. Si el mensaje muere, va a cmd.dead.dlx con certificate.dead. */
    @Bean
    Queue certificateQueue() {
        return mainQueue(names.queue().certificate(), names.routing().certificateDead());
    }

    /** Cola clásica q.cmd.email.dlq. No tiene consumidor ni dead-letter propio. */
    @Bean
    Queue emailDlq() {
        return QueueBuilder.durable(names.queue().emailDlq()).build();
    }

    /** Cola clásica q.cmd.crew.dlq. No tiene consumidor ni dead-letter propio. */
    @Bean
    Queue crewDlq() {
        return QueueBuilder.durable(names.queue().crewDlq()).build();
    }

    /** Cola clásica q.cmd.certificate.dlq. No tiene consumidor ni dead-letter propio. */
    @Bean
    Queue certificateDlq() {
        return QueueBuilder.durable(names.queue().certificateDlq()).build();
    }

    /** Binding direct: email.send enruta a q.cmd.email solo si la routing key es igual. */
    @Bean
    Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(directExchange()).with(names.routing().emailSend());
    }

    /** Binding topic: el patrón email.* (una palabra) enruta a q.cmd.email. */
    @Bean
    Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(topicExchange()).with(names.routing().emailTopic());
    }

    /** Binding direct: crew.ticket enruta a q.cmd.crew solo si la routing key es igual. */
    @Bean
    Binding crewDirectBinding() {
        return BindingBuilder.bind(crewQueue()).to(directExchange()).with(names.routing().crewTicket());
    }

    /** Binding topic: el patrón crew.# (cero o más palabras) enruta a q.cmd.crew. */
    @Bean
    Binding crewTopicBinding() {
        return BindingBuilder.bind(crewQueue()).to(topicExchange()).with(names.routing().crewTopic());
    }

    /** Binding direct: certificate.gen enruta a q.cmd.certificate solo si la routing key es igual. */
    @Bean
    Binding certificateDirectBinding() {
        return BindingBuilder.bind(certificateQueue()).to(directExchange())
                .with(names.routing().certificateGen());
    }

    /** Binding topic: el patrón certificate.* (una palabra) enruta a q.cmd.certificate. */
    @Bean
    Binding certificateTopicBinding() {
        return BindingBuilder.bind(certificateQueue()).to(topicExchange())
                .with(names.routing().certificateTopic());
    }

    /** Binding direct: email.dead, por igualdad, enruta a q.cmd.email.dlq, que no tiene consumidor. */
    @Bean
    Binding emailDeadBinding() {
        return BindingBuilder.bind(emailDlq()).to(deadExchange()).with(names.routing().emailDead());
    }

    /** Binding direct: crew.dead, por igualdad, enruta a q.cmd.crew.dlq, que no tiene consumidor. */
    @Bean
    Binding crewDeadBinding() {
        return BindingBuilder.bind(crewDlq()).to(deadExchange()).with(names.routing().crewDead());
    }

    /** Binding direct: certificate.dead, por igualdad, enruta a q.cmd.certificate.dlq, que no tiene consumidor. */
    @Bean
    Binding certificateDeadBinding() {
        return BindingBuilder.bind(certificateDlq()).to(deadExchange()).with(names.routing().certificateDead());
    }

    /** Cola clásica durable, no replicada, con dead-letter. Sin x-queue-type no es quorum. */
    private Queue mainQueue(String name, String deadRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(names.exchange().dead())
                .deadLetterRoutingKey(deadRoutingKey)
                .build();
    }
}
