package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
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

    @Test
    void transactionManagerCanBeResolvedInTheProductionApplicationContext() {
        try (ServiceScope scope = scopeFactory.createScope()) {
            scope.run(provider -> {
                assertNotNull(provider.getRequiredService(Transactions.class));
                return null;
            });
        }
    }

}
