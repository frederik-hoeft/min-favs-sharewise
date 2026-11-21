package de.hsh.favs.sharewise;

import java.util.List;
import java.util.stream.Stream;
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
     * Returns grants that broaden a module beyond its reviewed API contracts.
     * An unqualified business module name still allows its top-level API; only
     * wildcard named-interface grants and the explicitly constrained shared
     * and host-local system boundaries are forbidden.
     */
    static List<String> forbiddenDependencyGrants(ApplicationModules modules) {
        return modules.stream()
                .flatMap(module -> forbiddenDependencyGrants(
                        module.getIdentifier().toString(),
                        ApplicationModuleInformation.of(module.getBasePackage()).getDeclaredDependencies()))
                .sorted()
                .toList();
    }

    static Stream<String> forbiddenDependencyGrants(String module, List<String> declarations) {
        return declarations.stream()
                .filter(dependency -> isForbiddenGrant(module, dependency))
                .map(dependency -> module + " -> " + dependency);
    }

    private static boolean isForbiddenGrant(String module, String dependency) {
        // Modulith accepts whitespace around '::'. It also accepts both the
        // '*' (all named interfaces) and '**' (all published APIs) wildcards.
        String normalized = dependency.replaceAll("\\s+", "");
        return normalized.endsWith("::*") || normalized.endsWith("::**")
                || normalized.equals("shared")
                || module.equals("system") && !normalized.equals("shared::health");
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
