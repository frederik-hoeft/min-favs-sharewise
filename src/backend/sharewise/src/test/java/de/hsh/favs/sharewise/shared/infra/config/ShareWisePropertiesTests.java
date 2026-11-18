package de.hsh.favs.sharewise.shared.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.shared.test.ComponentTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

@ComponentTest
class ShareWisePropertiesTests {

    @Test
    void validPropertiesBindIntoImmutableRecord() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "sharewise.app.name=sharewise-test",
                    "sharewise.app.api-default-page-size=25").applyTo(context);
            context.register(TestPropertiesConfig.class);
            context.refresh();

            ShareWiseProperties properties = context.getBean(ShareWiseProperties.class);
            assertEquals("sharewise-test", properties.name());
            assertEquals(25, properties.apiDefaultPageSize());
        }
    }

    @Test
    void defaultsAreAppliedWhenPropertiesAreOmitted() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TestPropertiesConfig.class);
            context.refresh();

            ShareWiseProperties properties = context.getBean(ShareWiseProperties.class);
            assertEquals("sharewise", properties.name());
            assertEquals(50, properties.apiDefaultPageSize());
        }
    }

    @Test
    void invalidPropertiesFailContextStartupFast() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "sharewise.app.name=   ",
                    "sharewise.app.api-default-page-size=25").applyTo(context);
            context.register(TestPropertiesConfig.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "sharewise.app.name=sharewise",
                    "sharewise.app.api-default-page-size=0").applyTo(context);
            context.register(TestPropertiesConfig.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "sharewise.app.name=sharewise",
                    "sharewise.app.api-default-page-size=999").applyTo(context);
            context.register(TestPropertiesConfig.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShareWiseProperties.class)
    static class TestPropertiesConfig {
    }
}
