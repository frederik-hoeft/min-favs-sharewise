package de.hsh.favs.sharewise.shared.infra.di;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

/** Bridges Spring's standard bean resolution to the lexically active scope cache. */
final class SpringOperationScope implements Scope {

    static final String NAME = "operation";

    private final SpringServiceScopeFactory scopes;

    SpringOperationScope(SpringServiceScopeFactory scopes) {
        this.scopes = scopes;
    }

    @Override
    public Object get(String name, ObjectFactory<?> objectFactory) {
        return scopes.currentScope().getOrCreate(name, objectFactory);
    }

    @Override
    public Object remove(String name) {
        return scopes.currentScope().remove(name);
    }

    @Override
    public void registerDestructionCallback(String name, Runnable callback) {
        scopes.currentScope().registerDestructionCallback(name, callback);
    }

    @Override
    public Object resolveContextualObject(String key) {
        scopes.currentScope();
        return null;
    }

    @Override
    public String getConversationId() {
        return scopes.currentScope().id();
    }
}
