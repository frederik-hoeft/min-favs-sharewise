package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.application.access.CallerAdmission;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.invitation.GroupInvitationService;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupAccess;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipService;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenGenerator;
import de.hsh.favs.sharewise.shared.infra.security.ApplicationTokenParser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;

/**
 * Explicitly imported wiring for Group application-service tests: the production services of
 * the Group application, in-memory ports, a recording event publisher and recording
 * transactions. Never discovered by the application's own component scan.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({CallerAdmission.class, GroupCreationService.class, GroupQueryService.class, GroupMetadataService.class,
    GroupAccess.class, GroupMembershipService.class, GroupInvitationService.class})
class GroupApplicationTestConfiguration {

    // Spring caches this context; both component suites reset the same singleton fixtures.
    static final String FIXTURE_LOCK = "group-application-fixtures";

    @Bean
    InMemoryGroups inMemoryGroups() {
        return new InMemoryGroups();
    }

    @Bean
    InMemoryInvitations inMemoryInvitations(InMemoryGroups groups) {
        return new InMemoryInvitations(groups);
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
    RecordingGroupEvents groupEvents(InMemoryGroups groups, RecordingTransactions.Log transactions) {
        return new RecordingGroupEvents(groups, transactions);
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
