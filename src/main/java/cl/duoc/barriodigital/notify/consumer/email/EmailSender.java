package cl.duoc.barriodigital.notify.consumer.email;

@FunctionalInterface
public interface EmailSender {

    void send(EmailMessage email);
}
