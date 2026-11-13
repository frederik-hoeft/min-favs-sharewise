package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ImportOption;
import de.hsh.favs.modulithfixtures.implicitshared.ImplicitSharedApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Proves that module verification detects each forbidden dependency kind and
 * accepts declared dependencies on published APIs.
 *
 * <p>Each scenario is a small fixture application below
 * {@code de.hsh.favs.modulithfixtures}, outside the real application's root
 * package, whose direct sub-packages are its modules. Every negative scenario
 * contains exactly one violation, and its test asserts that verification
 * reports that violation rather than merely failing.
 *
 * <p>Fixtures are loaded by package name, which applies Spring Modulith's
 * default configuration. The {@code implicitshared} scenario is loaded from
 * its {@code @Modulithic} type instead, because that annotation carries its
 * defect.
 */
class ModuleVerificationFixtureTests {

    private static final String FIXTURES = "de.hsh.favs.modulithfixtures";

    /**
     * Fixtures live in test sources, which Spring Modulith excludes from its
     * analysis by default.
     */
    private static final ImportOption INCLUDE_TEST_CLASSES = location -> true;

    @Test
    void declaredDependenciesOnPublishedApisPass() {
        ApplicationModules modules = fixture("valid");

        Violations violations = modules.detectViolations();

        assertEquals(List.of(), violations.getMessages());
        assertEquals(List.of(), ModuleDependencyDeclarations.modulesWithoutDeclaredDependencies(modules));
        assertEquals(List.of(), ModuleDependencyDeclarations.globallySharedModules(modules));
        assertEquals(List.of(), ModulePublications.openModules(modules));
        assertEquals(List.of(), ModulePublications.publishedTypesDirectlyIn(modules, FIXTURES + ".valid.shared"));
    }

    @Test
    void accessToAnotherModulesInternalPackageIsRejected() {
        Violations violations = fixture("internalaccess").detectViolations();

        assertSingleViolation(violations,
                FIXTURES + ".internalaccess.consumer.Consumer",
                FIXTURES + ".internalaccess.provider.internal.ProviderInternal");
    }

    @Test
    void accessToAnUnpublishedPackageOfADeclaredModuleIsRejected() {
        Violations violations = fixture("unpublishedinterface").detectViolations();

        assertSingleViolation(violations,
                FIXTURES + ".unpublishedinterface.consumer.Consumer",
                FIXTURES + ".unpublishedinterface.shared.infra.di.internal.SharedImplementation");
    }

    @Test
    void dependencyMissingFromTheWhitelistIsRejected() {
        Violations violations = fixture("undeclareddependency").detectViolations();

        assertSingleViolation(violations,
                FIXTURES + ".undeclareddependency.consumer.Consumer",
                FIXTURES + ".undeclareddependency.provider.ProviderApi");
    }

    @Test
    void sharedModuleDependingOnABusinessModuleIsRejected() {
        Violations violations = fixture("sharedtobusiness").detectViolations();

        assertSingleViolation(violations,
                FIXTURES + ".sharedtobusiness.shared.infra.SharedInfrastructure",
                FIXTURES + ".sharedtobusiness.business.BusinessApi");
    }

    @Test
    void dependencyCycleBetweenModulesIsRejected() {
        Violations violations = fixture("cycle").detectViolations();

        assertSingleViolation(violations,
                FIXTURES + ".cycle.alpha.AlphaApi",
                FIXTURES + ".cycle.beta.BetaApi");
    }

    @Test
    void moduleWithoutAllowedDependenciesDeclarationIsReported() {
        ApplicationModules modules = fixture("missingdeclaration");

        assertEquals(List.of(), modules.detectViolations().getMessages(),
                "the scenario must fail only the declaration convention");
        assertEquals(List.of("consumer"), ModuleDependencyDeclarations.modulesWithoutDeclaredDependencies(modules));
    }

    @Test
    void globallySharedModuleIsReported() {
        ApplicationModules modules = ApplicationModules.of(ImplicitSharedApplication.class, INCLUDE_TEST_CLASSES);

        assertEquals(List.of(), modules.detectViolations().getMessages(),
                "a globally shared module lets the consumer use it undeclared, so a project check must detect it");
        assertEquals(List.of("shared"), ModuleDependencyDeclarations.globallySharedModules(modules));
    }

    @Test
    void openModuleIsReported() {
        ApplicationModules modules = fixture("openmodule");

        assertEquals(List.of(), modules.detectViolations().getMessages(),
                "the scenario must fail only the closed-module convention");
        assertEquals(List.of("provider"), ModulePublications.openModules(modules));
    }

    @Test
    void implementationPackagePublishedThroughAnotherNamedInterfaceIsReported() {
        ApplicationModules modules = fixture("publishedinternal");

        assertEquals(List.of(), modules.detectViolations().getMessages(),
                "publishing a package is not a verification violation, so a project check must detect it");
        assertEquals(List.of("shared :: di-internal publishes "
                        + FIXTURES + ".publishedinternal.shared.infra.di.internal.SharedImplementation"),
                ModulePublications.publishedTypesIn(modules,
                        FIXTURES + ".publishedinternal.shared.infra.di.internal"));
    }

    @Test
    void typePublishedFromSharedTopLevelPackageIsReported() {
        ApplicationModules modules = fixture("publishedtoplevel");

        assertEquals(List.of(), modules.detectViolations().getMessages(),
                "a public top-level type is published implicitly, so a project check must detect it");
        String unnamedInterface = modules.getModuleByName("shared").orElseThrow()
                .getNamedInterfaces().getUnnamedInterface().getName();
        assertEquals(List.of("shared :: " + unnamedInterface + " publishes "
                        + FIXTURES + ".publishedtoplevel.shared.SharedApi"),
                ModulePublications.publishedTypesDirectlyIn(modules, FIXTURES + ".publishedtoplevel.shared"));
    }

    private static ApplicationModules fixture(String scenario) {
        return ApplicationModules.of(FIXTURES + "." + scenario, INCLUDE_TEST_CLASSES);
    }

    private static void assertSingleViolation(Violations violations, String... expectedFragments) {
        List<String> messages = violations.getMessages();
        assertEquals(1, messages.size(), () -> "expected exactly one violation but got: " + messages);
        String message = messages.get(0);
        for (String fragment : expectedFragments) {
            assertTrue(message.contains(fragment),
                    () -> "violation must mention " + fragment + " but was: " + message);
        }
    }
}
