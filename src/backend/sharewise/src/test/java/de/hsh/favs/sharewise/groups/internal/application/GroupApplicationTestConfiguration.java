package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenParser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;

/**
 * Explicitly imported wiring for Group application-service tests: the production services
 * of this package, in-memory ports and recording transactions. Never discovered by the
 * application's own component scan.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({CallerAdmission.class, GroupCreationService.class, GroupQueryService.class, GroupMetadataService.class,
    GroupAccess.class, GroupMembershipService.class, GroupInvitationService.class})
class GroupApplicationTestConfiguration {

    @Bean
    InMemoryGroups inMemoryGroups() {
        return new InMemoryGroups();
    }

    @Bean
    InMemoryInvitations inMemoryInvitations() {
        return new InMemoryInvitations();
    }

    @Bean
    ApplicationTokenGenerator tokenGenerator() {
        return new ApplicationTokenGenerator();
    }

    @Bean
    ApplicationTokenParser tokenParser() {
        return new ApplicationTokenParser();
    }

    @Bean
    RecordingTransactions.Log transactionLog() {
        return new RecordingTransactions.Log();
    }

    @Bean
    @Scope("operation")
    RecordingTransactions transactions(RecordingTransactions.Log log) {
        return new RecordingTransactions(log);
    }
}
