package de.hsh.favs.modulithfixtures.identityboundary.groups;

import de.hsh.favs.modulithfixtures.identityboundary.identity.IdentityApi;
import de.hsh.favs.modulithfixtures.identityboundary.identity.internal.IdentityImplementation;

/** Published IAM access is permitted; implementation access is not. */
public final class GroupConsumer {

    private IdentityApi published;
    private IdentityImplementation forbidden;
}
