package cl.duoc.barriodigital.notify.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sin broker: con todas las claves la topología queda en el contexto; si falta una, no arranca.
 */
class RabbitNamesPropertiesTest {

    private static final String[] CONTRACT = {
            "barriodigital.rabbit.exchange.topic=cmd.topic",
            "barriodigital.rabbit.exchange.direct=cmd.direct",
            "barriodigital.rabbit.exchange.dead=cmd.dead.dlx",
            "barriodigital.rabbit.queue.email=q.cmd.email",
            "barriodigital.rabbit.queue.email-dlq=q.cmd.email.dlq",
            "barriodigital.rabbit.queue.crew=q.cmd.crew",
            "barriodigital.rabbit.queue.crew-dlq=q.cmd.crew.dlq",
            "barriodigital.rabbit.queue.certificate=q.cmd.certificate",
            "barriodigital.rabbit.queue.certificate-dlq=q.cmd.certificate.dlq",
            "barriodigital.rabbit.routing.email-send=email.send",
            "barriodigital.rabbit.routing.email-topic=email.*",
            "barriodigital.rabbit.routing.crew-ticket=crew.ticket",
            "barriodigital.rabbit.routing.crew-topic=crew.#",
            "barriodigital.rabbit.routing.certificate-gen=certificate.gen",
            "barriodigital.rabbit.routing.certificate-topic=certificate.*",
            "barriodigital.rabbit.routing.email-dead=email.dead",
            "barriodigital.rabbit.routing.crew-dead=crew.dead",
            "barriodigital.rabbit.routing.certificate-dead=certificate.dead"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopologyConfig.class);

    @Test
    void conTodasLasClavesDeclaraLaTopologiaCompleta() {
        runner.withPropertyValues(CONTRACT).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(Exchange.class)).hasSize(3);
            assertThat(context.getBeansOfType(Queue.class)).hasSize(6);
            assertThat(context.getBeansOfType(Binding.class)).hasSize(9);
            assertThat(context.getBean(RabbitNamesProperties.class).queue().emailDlq())
                    .isEqualTo("q.cmd.email.dlq");
        });
    }

    @Test
    void siFaltaUnaClaveElContextoNoArranca() {
        String[] withoutCrewDlq = Arrays.stream(CONTRACT)
                .filter(property -> !property.startsWith("barriodigital.rabbit.queue.crew-dlq="))
                .toArray(String[]::new);

        runner.withPropertyValues(withoutCrewDlq).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("queue.crewDlq");
        });
    }

    @Test
    void siFaltaUnBloqueCompletoElContextoNoArranca() {
        String[] withoutRouting = Arrays.stream(CONTRACT)
                .filter(property -> !property.startsWith("barriodigital.rabbit.routing."))
                .toArray(String[]::new);

        runner.withPropertyValues(withoutRouting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("routing");
        });
    }
}
