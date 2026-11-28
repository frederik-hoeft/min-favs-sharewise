package de.hsh.favs.sharewise.shared.infra.web;

import de.hsh.favs.sharewise.shared.infra.web.internal.SupplementaryHttpSecurityWiring;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Host integration boundary: ingress is composed with its required operation input pipeline. */
@Configuration(proxyBeanMethods = false)
@Import(SupplementaryHttpSecurityWiring.class)
public class SupplementaryHttpSecurityConfiguration { }
