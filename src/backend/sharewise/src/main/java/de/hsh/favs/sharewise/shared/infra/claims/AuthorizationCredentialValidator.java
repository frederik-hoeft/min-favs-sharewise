package de.hsh.favs.sharewise.shared.infra.claims;

/**
 * Extensible, ordered credential consistency check. All registered validators
 * must succeed before evidence is published. Validators cannot change the candidates
 * or resolve the application authorization snapshot during its construction.
 * Expected rejections are typed outcomes; thrown exceptions are infrastructure failures.
 */
public interface AuthorizationCredentialValidator {
    CredentialValidationResult validate(CredentialValidationContext context);
}
