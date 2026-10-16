package cl.duoc.barriodigital.notify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sin broker: el contexto levanta y el health responde UP porque src/test/resources apaga el indicador de RabbitMQ.
 * Fuera de los tests el indicador está activo y sin broker el health queda DOWN.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotifyApplicationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Test
    void elContextoCargaSinBroker() {
        assertNotNull(context.getBean(NotifyApplication.class));
    }

    @Test
    void healthRespondeUpSinBroker() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
