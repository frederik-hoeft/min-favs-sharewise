package de.hsh.favs.sharewise.groups.internal.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Keeps the Group application capabilities independent of circular package dependencies. */
class GroupApplicationPackageTests {

    private static final String APPLICATION = "de.hsh.favs.sharewise.groups.internal.application";

    @Test
    void applicationPackagesAreAcyclic() {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(APPLICATION);

        slices().matching(APPLICATION + ".(*)..").should().beFreeOfCycles().check(production);
        noClasses().that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAPackage(APPLICATION + ".*..")
            .check(production);
    }
}
