package cl.duoc.barriodigital.notify.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Carpeta donde se guardan los PDF.
 * Si nadie configura otra, usa ./data/certificates.
 */
@Validated
@ConfigurationProperties(prefix = "barriodigital.certificate")
public record CertificateProperties(@NotBlank @DefaultValue("./data/certificates") String outputDir) {
}
