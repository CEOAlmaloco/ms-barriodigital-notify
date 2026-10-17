package cl.duoc.barriodigital.notify.consumer.email;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailSenderTest {

    @Test
    void identificaSimulacionYTipoSinVolcarDatosPersonales() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new LoggingEmailSender().send(new EmailMessage("email.visit", "vecino@example.com", "Asunto", "Datos personales"));
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("email-simulado type=email.visit entrega-real=false");
        } finally {
            logger.detachAppender(logs);
        }
    }
}
