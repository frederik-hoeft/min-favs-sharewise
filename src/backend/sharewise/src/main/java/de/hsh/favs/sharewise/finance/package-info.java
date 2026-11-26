/**
 * Finance owns the financial group lifecycle, actor identities and financial history.
 *
 * <p>It consumes Group Management's integration events to project group roots and retained actors,
 * and publishes no Java API.
 */
@ApplicationModule(allowedDependencies = {
    "groups::events", "shared::di", "shared::jooq", "shared::result", "shared::transactions"})
package de.hsh.favs.sharewise.finance;

import org.springframework.modulith.ApplicationModule;
