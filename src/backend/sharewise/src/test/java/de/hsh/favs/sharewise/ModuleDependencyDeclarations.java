package de.hsh.favs.sharewise;

import java.util.List;
import org.springframework.modulith.core.ApplicationModuleInformation;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Checks the project conventions that every application module declares an
 * explicit {@code allowedDependencies} whitelist and that no dependency is
 * permitted outside those whitelists.
 *
 * <p>Spring Modulith treats a module without such a declaration as
 * unrestricted and reports it with the single marker entry
 * {@link org.springframework.modulith.ApplicationModule#OPEN_TOKEN}, whereas
 * an explicit {@code allowedDependencies = {}} yields an empty list.
 *
 * <p>Modules configured through {@code @Modulithic(sharedModules = ...)} are
 * added to every other module's allowed dependencies without appearing in any
 * declaration, so verification accepts undeclared uses of them.
 */
final class ModuleDependencyDeclarations {

    private static final List<String> UNDECLARED =
            List.of(org.springframework.modulith.ApplicationModule.OPEN_TOKEN);

    private ModuleDependencyDeclarations() {
    }

    /**
     * Returns the names of all modules that do not declare
     * {@code allowedDependencies}, sorted by name.
     */
    static List<String> modulesWithoutDeclaredDependencies(ApplicationModules modules) {
        return modules.stream()
                .filter(module -> UNDECLARED.equals(
                        ApplicationModuleInformation.of(module.getBasePackage()).getDeclaredDependencies()))
                .map(module -> module.getIdentifier().toString())
                .sorted()
                .toList();
    }

    /**
     * Returns the names of all modules configured as globally shared through
     * {@code @Modulithic(sharedModules = ...)}, sorted by name.
     */
    static List<String> globallySharedModules(ApplicationModules modules) {
        return modules.getSharedModules().stream()
                .map(module -> module.getIdentifier().toString())
                .sorted()
                .toList();
    }
}
