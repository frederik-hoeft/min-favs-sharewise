package de.hsh.favs.sharewise.shared.infra.di;

/**
 * Supplies one caller-context contribution before an application operation is dispatched.
 * Implementations belong to the outer DI context (for example an HTTP request), not
 * to the new operation scope. Multiple contributors may register distinct input types.
 */
@FunctionalInterface
public interface OperationInputContributor {

    /**
     * Registers available caller-owned inputs; missing optional inputs may be omitted.
     *
     * @param inputs fresh input registry for the pending dispatch
     */
    void contribute(OperationInputRegistry inputs);
}
