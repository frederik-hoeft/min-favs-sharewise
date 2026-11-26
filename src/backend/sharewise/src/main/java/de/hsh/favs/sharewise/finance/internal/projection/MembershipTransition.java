package de.hsh.favs.sharewise.finance.internal.projection;

import de.hsh.favs.sharewise.finance.internal.actor.FinancialActor;
import java.util.Objects;

/**
 * Decision of {@link MembershipTransitionPolicy}, carrying the snapshots its branch needs. Only
 * {@link Insert} and {@link Supersede} imply a write.
 */
sealed interface MembershipTransition {

    /** Retain a previously unknown actor. */
    record Insert(FinancialActor actor) implements MembershipTransition {
        public Insert {
            Objects.requireNonNull(actor, "actor");
        }
    }

    /** Replace the retained actor by a newer revision. */
    record Supersede(FinancialActor current, FinancialActor next) implements MembershipTransition {
        public Supersede {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(next, "next");
        }
    }

    /** The retained actor already equals the received one. */
    record Duplicate(FinancialActor current) implements MembershipTransition {
        public Duplicate {
            Objects.requireNonNull(current, "current");
        }
    }

    /** The retained actor already reflects a newer revision. */
    record Stale(FinancialActor current) implements MembershipTransition {
        public Stale {
            Objects.requireNonNull(current, "current");
        }
    }

    /** Same revision, different state: a producer contract violation. */
    record Contradiction(FinancialActor current, FinancialActor received) implements MembershipTransition {
        public Contradiction {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(received, "received");
        }
    }
}
