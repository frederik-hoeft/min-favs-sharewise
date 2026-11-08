package de.hsh.favs.sharewise.shared;

/** Creates independent, caller-owned dependency injection scopes. */
public interface ServiceScopeFactory {

    ServiceScope createScope();
}
