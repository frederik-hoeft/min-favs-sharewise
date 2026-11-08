package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.hsh.favs.sharewise.shared.ServiceScopeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ShareWiseApplicationTests {

    @Autowired
    ServiceScopeFactory scopeFactory;

    @Autowired
    ConfigurableListableBeanFactory beanFactory;

    @Test
    void contextLoads() {
        assertNotNull(scopeFactory);
        assertNotNull(beanFactory.getRegisteredScope("operation"));
    }

}
