package de.hsh.favs.sharewise.shared.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShareWiseProperties.class)
class ShareWisePropertiesConfiguration {
}
