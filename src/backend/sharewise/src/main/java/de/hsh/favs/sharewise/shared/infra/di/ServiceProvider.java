package de.hsh.favs.sharewise.shared.infra.di;

/** Resolves Spring-managed beans only while its owning scope is active. */
public interface ServiceProvider {

    <T> T getRequiredService(Class<T> serviceType);
}
