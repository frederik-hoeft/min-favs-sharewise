/** Finance owns the financial group lifecycle, actor identities and financial history. */
@ApplicationModule(allowedDependencies = {"shared::jooq", "shared::result"})
package de.hsh.favs.sharewise.finance;

import org.springframework.modulith.ApplicationModule;
