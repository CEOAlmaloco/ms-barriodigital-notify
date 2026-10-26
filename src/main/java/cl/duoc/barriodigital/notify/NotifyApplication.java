package cl.duoc.barriodigital.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Arranca notify.
 * Al iniciar declara la topología de RabbitMQ y queda escuchando las colas de correo, cuadrilla y PDF.
 */
@SpringBootApplication
public class NotifyApplication {

    /** Punto de entrada. El servicio queda en el puerto 8083. */
    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
