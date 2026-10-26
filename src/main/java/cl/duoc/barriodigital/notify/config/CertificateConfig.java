package cl.duoc.barriodigital.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Clock;

/**
 * Prepara el reloj de los certificados y activa la carpeta configurada.
 * El reloj marca en UTC la hora en que se generó cada PDF.
 */
@Configuration
@EnableConfigurationProperties(CertificateProperties.class)
public class CertificateConfig {

    /** Reloj UTC que estampa la hora de generación del PDF. */
    @Bean
    Clock certificateClock() {
        return Clock.systemUTC();
    }
}
