package de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal;

import de.hsh.favs.sharewise.shared.infra.persistence.jpa.JpaAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import org.springframework.context.annotation.Scope;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves JPA operations through Spring's transaction-bound persistence context. */
@Component
@Scope("operation")
final class SpringJpaAccess implements JpaAccess {

    private final TransactionSegmentAccess segments;
    private final EntityManagerFactory entityManagerFactory;
    private final EntityManager sharedEntityManager;

    SpringJpaAccess(TransactionSegmentAccess segments, EntityManagerFactory entityManagerFactory) {
        this.segments = Objects.requireNonNull(segments);
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory);
        this.sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Override
    public EntityManager entityManager() {
        TransactionSegmentAccess.SegmentLease lease = segments.captureCurrentSegment();
        // An active JDBC transaction alone does not imply that Hibernate shares
        // its persistence context. Require the context bound by JpaTransactionManager.
        if (!TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            throw new IllegalStateException("No Spring-managed JPA persistence context in this transaction");
        }
        return SegmentBoundJpaHandles.guard(sharedEntityManager, lease);
    }
}
