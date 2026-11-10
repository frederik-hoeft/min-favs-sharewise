package de.hsh.favs.sharewise;

import java.util.List;
import org.springframework.modulith.core.ApplicationModuleInformation;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Checks the project convention that every application module declares an
 * explicit {@code allowedDependencies} whitelist.
 *
 * <p>Spring Modulith treats a module without such a declaration as
 * unrestricted and reports it with the single marker entry
 * {@link org.springframework.modulith.ApplicationModule#OPEN_TOKEN}, whereas
 * an explicit {@code allowedDependencies = {}} yields an empty list.
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
}
