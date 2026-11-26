package de.hsh.favs.sharewise.shared.infra.di;

/**
 * Infrastructure admission check run after operation input initialization and
 * before the operation entry service is resolved. A failure prevents dispatch.
 * This is not a business authorization decision or a side-effect hook.
 */
@FunctionalInterface
public interface OperationAdmission {
    void admit();
}
