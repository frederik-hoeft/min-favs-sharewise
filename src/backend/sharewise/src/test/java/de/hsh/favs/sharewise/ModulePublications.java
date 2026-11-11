package de.hsh.favs.sharewise;

import java.util.List;
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
 * {@code shared.infra.di.internal} are never part of any named interface.
 * Publishing an implementation type through an additional or propagated
 * named interface, or by opening its module, is not a verification
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
        return modules.stream()
                .flatMap(module -> module.getNamedInterfaces().stream()
                        .flatMap(namedInterface -> publishedTypesIn(module, namedInterface, packageName)))
                .sorted()
                .toList();
    }

    private static Stream<String> publishedTypesIn(ApplicationModule module,
            NamedInterface namedInterface, String packageName) {
        return namedInterface.asJavaClasses()
                .filter(type -> !type.getSimpleName().equals("package-info"))
                .filter(type -> type.getPackageName().equals(packageName)
                        || type.getPackageName().startsWith(packageName + "."))
                .map(type -> module.getIdentifier() + " :: " + namedInterface.getName()
                        + " publishes " + type.getName());
    }
}
