package de.hsh.favs.p13crollback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA-only PostgreSQL probe; never included in production entity scanning. */
@Entity
@Table(schema = "test_probes", name = "p13c_rollback_probe")
public class RollbackProbeRow {

    @Id
    private Integer id;

    @Column(nullable = false)
    private String label;

    protected RollbackProbeRow() { }

    public RollbackProbeRow(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public Integer getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
