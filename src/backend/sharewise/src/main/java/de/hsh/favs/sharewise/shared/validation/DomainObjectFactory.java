package de.hsh.favs.sharewise.shared.validation;

import de.hsh.favs.sharewise.shared.result.Result;

/** Converts untrusted input into a validated application/domain object without transport coupling. */
@FunctionalInterface
public interface DomainObjectFactory<TDomainObject, TInput> {

    Result<TDomainObject, ValidationErrors> createFrom(TInput input);
}
