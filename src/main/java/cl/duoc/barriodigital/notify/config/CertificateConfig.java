package cl.duoc.barriodigital.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(CertificateProperties.class)
public class CertificateConfig {

    @Bean
    Clock certificateClock() {
        return Clock.systemUTC();
    }
}
