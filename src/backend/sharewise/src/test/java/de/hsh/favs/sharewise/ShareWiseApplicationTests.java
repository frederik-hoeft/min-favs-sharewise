package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.hsh.favs.sharewise.shared.infra.config.ShareWiseProperties;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.health.SystemHealthController;
import de.hsh.favs.sharewise.shared.infra.health.SystemHealthController.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest
class ShareWiseApplicationTests {

    @Autowired
    ServiceScopeFactory scopeFactory;

    @Autowired
    ConfigurableListableBeanFactory beanFactory;

    @Autowired
    ShareWiseProperties properties;

    @Autowired
    SystemHealthController healthController;

    @Test
    void contextLoads() {
        assertNotNull(scopeFactory);
        assertNotNull(beanFactory.getRegisteredScope("operation"));
        assertEquals("sharewise", properties.name());
        assertEquals(50, properties.apiDefaultPageSize());

        ResponseEntity<HealthResponse> health = healthController.health();
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertNotNull(health.getBody());
        assertEquals("UP", health.getBody().status());
    }

}
