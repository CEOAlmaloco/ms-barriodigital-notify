package cl.duoc.barriodigital.notify.config;

import cl.duoc.barriodigital.notify.consumer.email.EmailAddress;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Define si el correo se simula en el log o se envía por SMTP.
 * En modo smtp también guarda la dirección que aparece como remitente.
 */
@Validated
@ConfigurationProperties(prefix = "barriodigital.email")
public record EmailProperties(@NotNull @DefaultValue("logging") Mode mode, String from) {

    public enum Mode {
        LOGGING, SMTP
    }

    /** En modo smtp el remitente tiene que ser una sola dirección válida. En logging no se exige. */
    @AssertTrue(message = "barriodigital.email.from debe ser una direccion unica valida en modo smtp")
    public boolean isFromValid() {
        return mode != Mode.SMTP || EmailAddress.isValid(from);
    }
}
