package de.hsh.favs.sharewise.identity.internal.auth.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;

/** Ensure the production repository stereotypes can be proxied with Spring's class-based AOP. */
class IamRepositoryProxyTests {

    @Test
    void repositoryInterfacesResolveThroughClassBasedExceptionTranslationProxies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // This isolated test checks AOP bean creation, not operation-scope lifetime semantics.
            context.getBeanFactory().registerScope("operation", new SimpleThreadScope());
            context.register(Fixtures.class);
            context.refresh();

            assertTrue(AopUtils.isCglibProxy(context.getBean(AccountStore.class)));
            assertTrue(AopUtils.isCglibProxy(context.getBean(SessionStore.class)));
            assertTrue(AopUtils.isCglibProxy(context.getBean(RefreshTokenStore.class)));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({JooqAccountStore.class, JooqSessionStore.class, JooqRefreshTokenStore.class})
    static class Fixtures {

        @Bean
        static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslation() {
            PersistenceExceptionTranslationPostProcessor processor =
                    new PersistenceExceptionTranslationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
        }

        @Bean
        JooqAccess jooqAccess() {
            return () -> { throw new UnsupportedOperationException("No SQL in proxy wiring test"); };
        }
    }
}
