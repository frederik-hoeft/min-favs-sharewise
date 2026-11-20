package de.hsh.favs.sharewise.identity.internal.auth;

/** Expected registration rejection; the HTTP adapter maps this to a typed ProblemDetail. */
public enum RegistrationFailure {
    EMAIL_UNAVAILABLE
}
