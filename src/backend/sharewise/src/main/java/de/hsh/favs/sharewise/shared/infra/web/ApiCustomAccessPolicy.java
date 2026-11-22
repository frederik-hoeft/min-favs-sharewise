package de.hsh.favs.sharewise.shared.infra.web;

import jakarta.servlet.http.HttpServletRequest;

/** Optional HTTP admission gate for a module; invoked only after verified primary IAM identity. */
public interface ApiCustomAccessPolicy {
    String name();
    boolean allows(HttpServletRequest request);
}
