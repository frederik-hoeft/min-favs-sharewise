package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.IdentityPort;
import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleInformation;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module structure of the real application.
 *
 * <p>Module verification is static bytecode analysis and starts no Spring
 * application context.
 */
class ApplicationModuleStructureTests {

    private final ApplicationModules modules = ApplicationModules.of(ShareWiseApplication.class);

    @Test
    void applicationModuleStructureIsValid() {
        modules.verify();
    }

    @Test
    void everyModuleDeclaresAllowedDependencies() {
        assertEquals(List.of(), ModuleDependencyDeclarations.modulesWithoutDeclaredDependencies(modules),
                "every module must declare @ApplicationModule(allowedDependencies = {...})");
    }

    @Test
    void dependencyGrantsDoNotBroadenReviewedModuleBoundaries() {
        assertEquals(List.of(), ModuleDependencyDeclarations.forbiddenDependencyGrants(modules),
                "wildcard API grants, broad shared access and unexpected system dependencies are forbidden");
    }

    @Test
    void noModuleIsGloballyShared() {
        assertEquals(List.of(), ModuleDependencyDeclarations.globallySharedModules(modules),
                "modules must declare their dependencies on shared explicitly, not via @Modulithic(sharedModules = ...)");
    }

    @Test
    void everyModuleIsClosed() {
        assertEquals(List.of(), ModulePublications.openModules(modules),
                "every module must use the default CLOSED type, not @ApplicationModule(type = OPEN)");
    }

    @Test
    void sharedFoundationDeclaresNoModuleDependencies() {
        ApplicationModule shared = sharedModule();
        assertEquals(List.of(),
                ApplicationModuleInformation.of(shared.getBasePackage()).getDeclaredDependencies(),
                "shared infrastructure must declare no outgoing module dependencies");
    }

    @Test
    void identityTopLevelPublishesOnlyTheIntentionalIamContracts() {
        ApplicationModule identity = modules.getModuleByName("identity")
                .orElseThrow(() -> new AssertionError("module 'identity' must exist"));
        Set<String> publishedTypes = identity.getNamedInterfaces().getUnnamedInterface().asJavaClasses()
                .map(type -> type.getName())
                .filter(name -> !name.endsWith(".package-info"))
                .collect(Collectors.toSet());
        assertEquals(Set.of(IdentityContext.class.getName(), UnauthenticatedCallerException.class.getName(),
                IdentityPort.class.getName(), VerifiedIdentity.class.getName(),
                IdentityVerificationFailure.class.getName()), publishedTypes);
    }

    @Test
    void groupsDeclaresExactlyItsConsumedContractsAndPublishesNoTypes() {
        ApplicationModule groups = modules.getModuleByName("groups")
                .orElseThrow(() -> new AssertionError("module 'groups' must exist"));
        assertEquals(List.of("identity", "shared::di", "shared::jooq", "shared::result", "shared::security", "shared::transactions",
                "shared::validation", "shared::web"),
                ApplicationModuleInformation.of(groups.getBasePackage()).getDeclaredDependencies(),
                "groups may consume only the IAM identity contract and its declared shared contracts");
        assertEquals(List.of(), ModulePublications.publishedTypesDirectlyIn(modules, groups.getBasePackage().getName()),
                "groups publishes no Java API until a consumer needs one");
    }

    @Test
    void financePublishesNoApiAndDependsOnlyOnJooqResultAndTransactions() {
        ApplicationModule finance = modules.getModuleByName("finance")
                .orElseThrow(() -> new AssertionError("module 'finance' must exist"));
        assertEquals(List.of("shared::jooq", "shared::result", "shared::transactions"),
                ApplicationModuleInformation.of(finance.getBasePackage()).getDeclaredDependencies());
        assertEquals(List.of(), ModulePublications.publishedTypesDirectlyIn(modules,
                finance.getBasePackage().getName()));
    }

    @Test
    void implementationPackagesAreNeverPublished() {
        assertEquals(List.of(), ModulePublications.publishedTypesInInternalPackages(modules),
                "no module may publish types declared in an internal implementation package");
    }

    @Test
    void sharedTopLevelPackagePublishesNoTypes() {
        String sharedBasePackage = sharedModule().getBasePackage().getName();

        assertEquals(List.of(), ModulePublications.publishedTypesDirectlyIn(modules, sharedBasePackage),
                "shared publishes capabilities only through dedicated named interfaces, never from "
                        + sharedBasePackage);
    }

    private ApplicationModule sharedModule() {
        return modules.getModuleByName("shared")
                .orElseThrow(() -> new AssertionError("module 'shared' must exist"));
    }
}
