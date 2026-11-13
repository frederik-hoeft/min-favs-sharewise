package de.hsh.favs.sharewise;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

/**
 * Checks which types the modules of an application publish to other modules.
 *
 * <p>Spring Modulith verifies that consumers use only published types, but
 * deciding <em>what</em> is published is a project rule: every module is
 * closed, and implementation packages such as
 * {@code shared.infra.di.internal} are never part of any named interface,
 * and {@code shared} publishes nothing from its top-level package. Publishing
 * an implementation type through an additional or propagated named
 * interface, adding a {@code public} type to a top-level package that Spring
 * Modulith publishes implicitly, or opening a module is not a verification
 * violation, so these checks inspect the module model directly.
 */
final class ModulePublications {

    private ModulePublications() {
    }

    /**
     * Returns the names of all modules declared as
     * {@code @ApplicationModule(type = OPEN)}, sorted by name. An open module
     * publishes all of its types and is excluded from cycle detection.
     */
    static List<String> openModules(ApplicationModules modules) {
        return modules.stream()
                .filter(ApplicationModule::isOpen)
                .map(module -> module.getIdentifier().toString())
                .sorted()
                .toList();
    }

    /**
     * Returns every type in {@code packageName} or one of its sub-packages
     * that any named interface of any module publishes, including a module's
     * unnamed top-level interface. Each entry has the form
     * {@code "<module> :: <interface> publishes <type>"}; entries are sorted.
     */
    static List<String> publishedTypesIn(ApplicationModules modules, String packageName) {
        return publishedTypesMatching(modules, typePackage -> typePackage.equals(packageName)
                || typePackage.startsWith(packageName + "."));
    }

    /**
     * Returns every type declared directly in {@code packageName}, excluding
     * its sub-packages, that any named interface of any module publishes,
     * including a module's unnamed top-level interface. Each entry has the
     * same form as in {@link #publishedTypesIn}.
     */
    static List<String> publishedTypesDirectlyIn(ApplicationModules modules, String packageName) {
        return publishedTypesMatching(modules, typePackage -> typePackage.equals(packageName));
    }

    private static List<String> publishedTypesMatching(ApplicationModules modules,
            Predicate<String> typePackageFilter) {
        return modules.stream()
                .flatMap(module -> module.getNamedInterfaces().stream()
                        .flatMap(namedInterface -> publishedTypesMatching(module, namedInterface,
                                typePackageFilter)))
                .sorted()
                .toList();
    }

    private static Stream<String> publishedTypesMatching(ApplicationModule module,
            NamedInterface namedInterface, Predicate<String> typePackageFilter) {
        return namedInterface.asJavaClasses()
                .filter(type -> !type.getSimpleName().equals("package-info"))
                .filter(type -> typePackageFilter.test(type.getPackageName()))
                .map(type -> module.getIdentifier() + " :: " + namedInterface.getName()
                        + " publishes " + type.getName());
    }
}
