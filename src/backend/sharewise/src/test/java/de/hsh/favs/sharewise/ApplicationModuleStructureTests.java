package de.hsh.favs.sharewise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.di.ServiceProvider;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScope;
import de.hsh.favs.sharewise.shared.infra.di.ServiceScopeFactory;
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

    private static final String DI_INTERNAL_PACKAGE = "de.hsh.favs.sharewise.shared.infra.di.internal";

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
    void sharedIsTheOnlyModuleAndIsClosedWithoutModuleDependencies() {
        List<String> moduleNames = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .toList();
        assertEquals(List.of("shared"), moduleNames);

        ApplicationModule shared = sharedModule();
        assertFalse(shared.isOpen(), "shared must be a closed module");
        assertEquals(List.of(),
                ApplicationModuleInformation.of(shared.getBasePackage()).getDeclaredDependencies(),
                "shared must declare an empty allowedDependencies whitelist");
    }

    @Test
    void sharedDiNamedInterfacePublishesExactlyTheDiContracts() {
        NamedInterface di = sharedModule().getNamedInterfaces().getByName("di")
                .orElseThrow(() -> new AssertionError("shared must publish the named interface 'di'"));

        Set<String> publishedTypes = di.asJavaClasses()
                .map(type -> type.getName())
                .filter(name -> !name.endsWith(".package-info"))
                .collect(Collectors.toSet());

        Set<String> expectedTypes = Set.of(
                OperationDispatcher.class.getName(),
                ServiceScopeFactory.class.getName(),
                ServiceScope.class.getName(),
                ServiceProvider.class.getName());
        assertEquals(expectedTypes, publishedTypes);
    }

    @Test
    void noNamedInterfacePublishesDiImplementationTypes() {
        assertEquals(List.of(), ModulePublications.publishedTypesIn(modules, DI_INTERNAL_PACKAGE),
                "no named interface of any module may publish types from " + DI_INTERNAL_PACKAGE);
    }

    private ApplicationModule sharedModule() {
        return modules.getModuleByName("shared")
                .orElseThrow(() -> new AssertionError("module 'shared' must exist"));
    }
}
