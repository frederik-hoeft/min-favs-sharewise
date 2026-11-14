package de.hsh.favs.modulithfixtures.implicitshared;

import org.springframework.modulith.Modulithic;

/**
 * Declares {@code shared} as a globally shared module: the only defect of
 * this scenario.
 */
@Modulithic(sharedModules = "shared")
public final class ImplicitSharedApplication {

    private ImplicitSharedApplication() {
    }
}
