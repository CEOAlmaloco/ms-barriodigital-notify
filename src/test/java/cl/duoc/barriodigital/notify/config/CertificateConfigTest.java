package cl.duoc.barriodigital.notify.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CertificateConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CertificateConfig.class);

    @Test
    void directorioPredeterminadoYRelojUtc() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CertificateProperties.class).outputDir()).isEqualTo("./data/certificates");
            assertThat(context.getBean(java.time.Clock.class).getZone()).isEqualTo(java.time.ZoneOffset.UTC);
        });
    }

    @Test
    void directorioVacioImpideArranque() {
        runner.withPropertyValues("barriodigital.certificate.output-dir= ").run(context ->
                assertThat(context).hasFailed());
    }
}
