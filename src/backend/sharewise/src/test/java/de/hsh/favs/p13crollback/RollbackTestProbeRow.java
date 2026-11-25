package de.hsh.favs.p13crollback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Dedicated entity for the rollback integration suite, outside production entity discovery. */
@Entity(name = "RollbackTestProbeRow")
@Table(name = "rollback_tx_probe", schema = "test_probes")
public class RollbackTestProbeRow {
    @Id
    private Integer id;
    @Column(nullable = false)
    private String label;

    protected RollbackTestProbeRow() { }

    public RollbackTestProbeRow(int id, String label) {
        this.id = id;
        this.label = label;
    }
}
