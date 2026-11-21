package de.hsh.favs.sharewise;

import de.hsh.favs.sharewise.identity.transport.IamApiSecurityContribution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Application-wide HTTP security composition. One API chain covers every bounded
 * context; IAM contributes primary bearer admission and credential-entry CSRF/Origin
 * protections without owning another context's routing or authorization policy.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ApiHttpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, IamApiSecurityContribution iam)
            throws Exception {
        // All API routes receive the same trusted bearer admission, including
        // future Group endpoints. Operations enforce their own IdentityContext policies.
        HttpSecurity api = statelessTransport(http)
            .securityMatcher(PathPatternRequestMatcher.pathPattern("/api/**"))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        iam.contribute(api);
        return api.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain nonApiSecurityFilterChain(HttpSecurity http) throws Exception {
        // An explicit public fallback prevents implicit login/basic-auth defaults.
        return statelessTransport(http)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .build();
    }

    private static HttpSecurity statelessTransport(HttpSecurity http) throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable());
    }
}
