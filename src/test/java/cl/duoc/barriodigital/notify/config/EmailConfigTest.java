package cl.duoc.barriodigital.notify.config;

import cl.duoc.barriodigital.notify.consumer.email.EmailSender;
import cl.duoc.barriodigital.notify.consumer.email.LoggingEmailSender;
import cl.duoc.barriodigital.notify.consumer.email.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class EmailConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class, MailSenderAutoConfiguration.class))
            .withUserConfiguration(EmailConfig.class);

    private final String[] smtp = {
            "barriodigital.email.mode=smtp",
            "barriodigital.email.from=avisos@example.com",
            "spring.mail.host=localhost",
            "spring.mail.port=2525",
            "spring.mail.properties[mail.smtp.connectiontimeout]=5000",
            "spring.mail.properties[mail.smtp.timeout]=5000",
            "spring.mail.properties[mail.smtp.writetimeout]=5000"
    };

    @Test
    void loggingEsPredeterminadoSinHostRemitenteOCredenciales() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(LoggingEmailSender.class);
            assertThat(context).doesNotHaveBean(JavaMailSender.class);
            assertThat(context.getBean(EmailProperties.class).mode()).isEqualTo(EmailProperties.Mode.LOGGING);
        });
    }

    @Test
    void smtpExplicitoTieneUnSoloSenderYNoConectaAlCrearElContexto() {
        runner.withPropertyValues(smtp).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class).hasSingleBean(JavaMailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
            JavaMailSenderImpl transport = context.getBean(JavaMailSenderImpl.class);
            assertThat(transport.getHost()).isEqualTo("localhost");
            assertThat(transport.getPort()).isEqualTo(2525);
            assertThat(transport.getUsername()).isNull();
            assertThat(transport.getPassword()).isNull();
            assertThat(transport.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "5000").containsEntry("mail.smtp.writetimeout", "5000");
        });
    }

    @Test
    void yamlEnlazaVariablesSmtpYTimeoutsPredeterminadosSinConectar() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("barriodigital.email.mode=smtp", "EMAIL_FROM=avisos@example.com",
                        "SMTP_HOST=localhost", "SMTP_PORT=2525", "SMTP_AUTH=true", "SMTP_USER=usuario-demo",
                        "SMTP_PASSWORD=clave-ficticia", "SMTP_STARTTLS_ENABLE=true", "SMTP_STARTTLS_REQUIRED=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
                    JavaMailSenderImpl transport = context.getBean(JavaMailSenderImpl.class);
                    assertThat(transport.getHost()).isEqualTo("localhost");
                    assertThat(transport.getPort()).isEqualTo(2525);
                    assertThat(transport.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout", "5000")
                            .containsEntry("mail.smtp.timeout", "5000").containsEntry("mail.smtp.writetimeout", "5000")
                            .containsEntry("mail.smtp.auth", "true").containsEntry("mail.smtp.starttls.enable", "true")
                            .containsEntry("mail.smtp.starttls.required", "true");
                });
    }

    @Test
    void modoDesconocidoFallaConLaPropiedadIdentificada() {
        runner.withPropertyValues("barriodigital.email.mode=otro").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("barriodigital.email.mode");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-es-correo", "a@example.com,b@example.com"})
    void smtpRequiereRemitenteValido(String from) {
        runner.withPropertyValues(smtp).withPropertyValues("barriodigital.email.from=" + from).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("barriodigital.email.from");
        });
    }

    @Test
    void smtpRequiereHost() {
        runner.withPropertyValues(smtp).withPropertyValues("spring.mail.host=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.mail.host es obligatorio");
        });
    }

    @Test
    void smtpSinPropiedadHostFallaConMensajeClaro() {
        runner.withPropertyValues("barriodigital.email.mode=smtp", "barriodigital.email.from=avisos@example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.mail.host es obligatorio");
                });
    }

    @Test
    void smtpAutenticadoRequiereCredenciales() {
        runner.withPropertyValues(smtp).withPropertyValues("spring.mail.properties[mail.smtp.auth]=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.mail.username y spring.mail.password");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "no-es-numero"})
    void smtpRechazaTimeoutNoFinito(String timeout) {
        runner.withPropertyValues(smtp).withPropertyValues("spring.mail.properties[mail.smtp.timeout]=" + timeout)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("mail.smtp.timeout");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536"})
    void smtpRechazaPuertoFueraDeRango(String port) {
        runner.withPropertyValues(smtp).withPropertyValues("spring.mail.port=" + port).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.mail.port");
        });
    }
}
