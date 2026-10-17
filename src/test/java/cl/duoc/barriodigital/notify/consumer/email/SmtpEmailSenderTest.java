package cl.duoc.barriodigital.notify.consumer.email;

import cl.duoc.barriodigital.notify.messaging.NonRecoverableException;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SmtpEmailSenderTest {

    private final JavaMailSender transport = mock(JavaMailSender.class);
    private final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    private final SmtpEmailSender sender = new SmtpEmailSender(transport, "avisos@example.com");
    private final EmailMessage email = new EmailMessage("email.visit", "Vecino@example.com",
            "BarrioDigital: trámite en terreno", "Atención pública en Ñuñoa.\n\nTrámite: reparación");

    @BeforeEach
    void setUp() {
        when(transport.createMimeMessage()).thenReturn(message);
    }

    @Test
    void enviaUnaVezTextoPlanoUtf8SinAdjuntos() throws Exception {
        sender.send(email);
        message.saveChanges();

        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("avisos@example.com");
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo(email.to());
        assertThat(message.getSubject()).isEqualTo(email.subject());
        assertThat(message.isMimeType("text/plain")).isTrue();
        assertThat(message.getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(message.getContent()).isEqualTo(email.body());
        MimeMessage decoded = new MimeMessage(Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(serialized()));
        assertThat(decoded.getSubject()).isEqualTo(email.subject());
        assertThat(decoded.getContent().toString().replace("\r\n", "\n")).isEqualTo(email.body());
        verify(transport).createMimeMessage();
        verify(transport).send(message);
        verifyNoMoreInteractions(transport);
    }

    static Stream<RuntimeException> temporaryFailures() throws Exception {
        return Stream.of(
                new MailSendException("transport", new ConnectException()),
                new MailSendException("transport", new MessagingException("wrapped", new SocketTimeoutException())),
                new MailSendException("transport", new MessagingException("wrapped", new SocketException())),
                new MailSendException("transport", new SMTPSendFailedException("DATA", 451, "temporary", null, null, null, null)),
                new MailSendException("transport", new SendFailedException("temporary",
                        new SMTPAddressFailedException(new InternetAddress("vecino@example.com"), "RCPT TO", 450, "temporary"),
                        null, null, new InternetAddress[]{new InternetAddress("vecino@example.com")})),
                new MailSendException(Map.of("message", new SMTPAddressFailedException(
                        new InternetAddress("vecino@example.com"), "RCPT TO", 450, "temporary"))));
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void clasificaTemporalesInclusoEnCausasYMensajesFallidos(RuntimeException failure) {
        doThrow(failure).when(transport).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.send(email)).isInstanceOf(RecoverableException.class).hasCause(failure);
        verify(transport).send(message);
    }

    static Stream<RuntimeException> permanentFailures() throws Exception {
        MessagingException chained = new MessagingException("outer");
        chained.setNextException(new AuthenticationFailedException("rejected"));
        return Stream.of(
                new MailAuthenticationException("rejected"),
                new MailSendException("transport", chained),
                new MailSendException("transport", new AddressException("invalid")),
                new MailSendException("transport", new SendFailedException("invalid", null, null, null,
                        new InternetAddress[]{new InternetAddress("vecino@example.com")})),
                new MailSendException(Map.of("message", new SMTPAddressFailedException(
                        new InternetAddress("vecino@example.com"), "RCPT TO", 550, "permanent"))),
                new MailSendException("transport", new SMTPSendFailedException("DATA", 554, "permanent", null, null, null, null)));
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void clasificaPermanentesConservandoLaCausa(RuntimeException failure) {
        doThrow(failure).when(transport).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.send(email)).isInstanceOf(NonRecoverableException.class).hasCause(failure);
        verify(transport).send(message);
    }

    @Test
    void noClasificaMailSendExceptionSinCausaComoPermanente() {
        MailSendException failure = new MailSendException("unknown");
        doThrow(failure).when(transport).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.send(email)).isSameAs(failure);
    }

    @Test
    void conservaExcepcionInesperadaParaLaPoliticaCompartida() {
        IllegalStateException failure = new IllegalStateException("unexpected");
        doThrow(failure).when(transport).createMimeMessage();
        assertThatThrownBy(() -> sender.send(email)).isSameAs(failure);
    }

    private byte[] serialized() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        message.writeTo(bytes);
        return bytes.toByteArray();
    }
}
