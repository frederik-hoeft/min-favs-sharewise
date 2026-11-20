package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.IamOperationIngress;
import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.OperationInputRegistry;
import de.hsh.favs.sharewise.shared.infra.di.OperationScopedConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.ScopedServiceInitializer;
import de.hsh.favs.sharewise.shared.infra.di.ServiceProvider;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.FieldErrorDetail;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleInformation;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

/**
 * Verifies the module structure of the real application.
 *
 * <p>Module verification is static bytecode analysis and starts no Spring
 * application context.
 */
class ApplicationModuleStructureTests {

    private static final String CLAIMS_INTERNAL_PACKAGE = "de.hsh.favs.sharewise.shared.infra.claims.internal";
    private static final String DI_INTERNAL_PACKAGE = "de.hsh.favs.sharewise.shared.infra.di.internal";
    private static final String WEB_INTERNAL_PACKAGE = "de.hsh.favs.sharewise.shared.infra.web.internal";
    private static final String HEALTH_INTERNAL_PACKAGE = "de.hsh.favs.sharewise.shared.infra.health.internal";

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
    void identitySharedAndSystemModulesHaveExplicitDependencies() {
        Set<String> moduleNames = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("identity", "shared", "system"), moduleNames);

        ApplicationModule identity = modules.getModuleByName("identity")
                .orElseThrow(() -> new AssertionError("module 'identity' must exist"));
        assertFalse(identity.isOpen(), "identity must be a closed module");
        assertEquals(List.of("shared::claims", "shared::di", "shared::security", "shared::web"),
                ApplicationModuleInformation.of(identity.getBasePackage()).getDeclaredDependencies(),
                "identity may consume only its declared shared foundation contracts");

        ApplicationModule shared = sharedModule();
        assertFalse(shared.isOpen(), "shared must be a closed module");
        assertEquals(List.of(),
                ApplicationModuleInformation.of(shared.getBasePackage()).getDeclaredDependencies(),
                "shared must declare an empty allowedDependencies whitelist");

        ApplicationModule system = modules.getModuleByName("system")
                .orElseThrow(() -> new AssertionError("module 'system' must exist"));
        assertFalse(system.isOpen(), "system must be a closed module");
        assertEquals(List.of("shared :: health"),
                ApplicationModuleInformation.of(system.getBasePackage()).getDeclaredDependencies(),
                "the system endpoint must depend only on the published readiness contract");
    }

    @Test
    void identityTopLevelPublishesOnlyTheIntentionalIamContracts() {
        ApplicationModule identity = modules.getModuleByName("identity")
                .orElseThrow(() -> new AssertionError("module 'identity' must exist"));
        Set<String> publishedTypes = identity.getNamedInterfaces().getUnnamedInterface().asJavaClasses()
                .map(type -> type.getName())
                .filter(name -> !name.endsWith(".package-info"))
                .collect(Collectors.toSet());
        assertEquals(Set.of(IdentityContext.class.getName(), IamOperationIngress.class.getName(),
                UnauthenticatedCallerException.class.getName()),
                publishedTypes);
    }

    @Test
    void identityWebInterfacePublishesOnlyItsHttpIngressContract() {
        ApplicationModule identity = modules.getModuleByName("identity").orElseThrow();
        NamedInterface web = identity.getNamedInterfaces().getByName("web").orElseThrow();
        assertEquals(Set.of(de.hsh.favs.sharewise.identity.web.HttpIamOperationIngress.class.getName()),
            web.asJavaClasses().map(type -> type.getName())
                .filter(name -> !name.endsWith(".package-info")).collect(Collectors.toSet()));
    }

    @Test
    void sharedDiNamedInterfacePublishesExactlyTheDiContracts() {
        Set<String> publishedTypes = publishedTypesOfSharedInterface("di");

        Set<String> expectedTypes = Set.of(
                OperationDispatcher.class.getName(),
                OperationInputRegistry.class.getName(),
                OperationScopedConfiguration.class.getName(),
                ScopedServiceInitializer.class.getName(),
                ServiceScopeFactory.class.getName(),
                ServiceScope.class.getName(),
                ServiceProvider.class.getName());
        assertEquals(expectedTypes, publishedTypes);
    }

    @Test
    void sharedResultNamedInterfacePublishesExactlyTheResultContracts() {
        Set<String> publishedTypes = publishedTypesOfSharedInterface("result");

        Set<String> expectedTypes = Set.of(
                Result.class.getName(),
                Result.Ok.class.getName(),
                Result.Err.class.getName());
        assertEquals(expectedTypes, publishedTypes);
    }

    @Test
    void sharedWebNamedInterfacePublishesExactlyTheControllerErrorContracts() {
        Set<String> publishedTypes = publishedTypesOfSharedInterface("web");

        Set<String> expectedTypes = Set.of(
                ApiProblems.class.getName(),
                FieldErrorDetail.class.getName(),
                RequestValidationException.class.getName());
        assertEquals(expectedTypes, publishedTypes);
    }

    @Test
    void sharedClaimsNamedInterfacePublishesOnlyEvidenceContracts() {
        assertEquals(Set.of(
                Claim.class.getName(),
                ClaimOrigin.class.getName(),
                AuthorizationCredential.class.getName(),
                AuthorizationCredentialProvider.class.getName(),
                AuthorizationContext.class.getName()), publishedTypesOfSharedInterface("claims"));
    }

    @Test
    void sharedSecurityNamedInterfacePublishesOnlyTrustedJwtContracts() {
        assertEquals(Set.of(
            JwtCredentialCodec.class.getName(),
            JwtCredentialException.class.getName(),
            JwtCredentialProfile.class.getName(),
            JwtCredentialProfile.Kind.class.getName(),
            JwtIssueRequest.class.getName(),
            JwtEcKey.class.getName(),
            de.hsh.favs.sharewise.shared.infra.security.JwtSignatureService.class.getName(),
            de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService.class.getName(),
            VerifiedJwtCredential.class.getName()), publishedTypesOfSharedInterface("security"));
    }

    @Test
    void noNamedInterfacePublishesClaimsImplementationTypes() {
        assertEquals(List.of(), ModulePublications.publishedTypesIn(modules, CLAIMS_INTERNAL_PACKAGE),
                "claims implementation must not be published");
    }

    @Test
    void sharedHealthNamedInterfacePublishesOnlyTheReadinessContract() {
        assertEquals(Set.of(ReadinessIndicator.class.getName()), publishedTypesOfSharedInterface("health"));
    }

    @Test
    void noNamedInterfacePublishesHealthImplementationTypes() {
        assertEquals(List.of(), ModulePublications.publishedTypesIn(modules, HEALTH_INTERNAL_PACKAGE),
                "readiness-probe implementations must not be shared module APIs");
    }

    @Test
    void noNamedInterfacePublishesDiImplementationTypes() {
        assertEquals(List.of(), ModulePublications.publishedTypesIn(modules, DI_INTERNAL_PACKAGE),
                "no named interface of any module may publish types from " + DI_INTERNAL_PACKAGE);
    }

    @Test
    void noNamedInterfacePublishesWebImplementationTypes() {
        assertEquals(List.of(), ModulePublications.publishedTypesIn(modules, WEB_INTERNAL_PACKAGE),
                "no named interface of any module may publish types from " + WEB_INTERNAL_PACKAGE);
    }

    @Test
    void sharedTopLevelPackagePublishesNoTypes() {
        String sharedBasePackage = sharedModule().getBasePackage().getName();

        assertEquals(List.of(), ModulePublications.publishedTypesDirectlyIn(modules, sharedBasePackage),
                "shared publishes capabilities only through dedicated named interfaces, never from "
                        + sharedBasePackage);
    }

    private Set<String> publishedTypesOfSharedInterface(String interfaceName) {
        NamedInterface namedInterface = sharedModule().getNamedInterfaces().getByName(interfaceName)
                .orElseThrow(() -> new AssertionError("shared must publish the named interface '" + interfaceName + "'"));
        return namedInterface.asJavaClasses()
                .map(type -> type.getName())
                .filter(name -> !name.endsWith(".package-info"))
                .collect(Collectors.toSet());
    }

    private ApplicationModule sharedModule() {
        return modules.getModuleByName("shared")
                .orElseThrow(() -> new AssertionError("module 'shared' must exist"));
    }
}
