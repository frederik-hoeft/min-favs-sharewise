package de.hsh.favs.sharewise.shared.infra.di;

/** Creates independent, caller-owned dependency injection scopes. */
public interface ServiceScopeFactory {

    ServiceScope createScope();
}
