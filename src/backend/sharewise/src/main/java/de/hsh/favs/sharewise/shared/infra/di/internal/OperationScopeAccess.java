package de.hsh.favs.sharewise.shared.infra.di.internal;

/** Internal bridge for infrastructure that must validate its captured operation-scope lifetime. */
public interface OperationScopeAccess {

    /** Captures the currently active scope without extending its lifetime. */
    ScopeLease captureCurrent();

    /** An identity- and thread-bound scope check, not a service resolver. */
    @FunctionalInterface
    interface ScopeLease {
        void requireCurrent();
    }
}
