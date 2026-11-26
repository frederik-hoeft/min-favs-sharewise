package de.hsh.favs.sharewise.shared.infra.claims;

/**
 * Extensible, ordered credential consistency check. All registered validators
 * must succeed before evidence is published. Validators cannot change the candidates
 * or resolve the application authorization snapshot during its construction.
 */
public interface AuthorizationCredentialValidator {
    void validate(CredentialValidationContext context);
}
