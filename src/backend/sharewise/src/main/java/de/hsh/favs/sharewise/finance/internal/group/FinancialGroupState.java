package de.hsh.favs.sharewise.finance.internal.group;

/** Finance-owned lifecycle, independent of membership projection freshness. */
public enum FinancialGroupState {
    PROVISIONING,
    OPEN,
    CLOSED,
    ARCHIVED
}
