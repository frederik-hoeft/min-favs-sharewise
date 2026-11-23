package de.hsh.favs.sharewise.identity.internal.verification;

import de.hsh.favs.sharewise.identity.IdentityVerificationFailure;
import de.hsh.favs.sharewise.identity.VerifiedIdentity;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** IAM's authoritative current-account/session check. Owns its own completed read segment. */
final class IamIdentityVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IamIdentityVerificationService.class);

    private final Transactions transactions;
    private final IdentityEligibilityQuery eligibility;
    private final Clock clock;

    IamIdentityVerificationService(Transactions transactions, IdentityEligibilityQuery eligibility, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Result<VerifiedIdentity, IdentityVerificationFailure> verify(UUID userId, UUID sessionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        return transactions.execute(() -> {
            Instant now = clock.instant();
            try {
                if (!eligibility.isEligible(userId, sessionId, now)) {
                    return TransactionOutcome.abstain(Result.err(IdentityVerificationFailure.INELIGIBLE));
                }
                return TransactionOutcome.abstain(Result.ok(new VerifiedIdentity(userId, sessionId, now)));
            } catch (EligibilityQueryUnavailableException outage) {
                // Only adapter-classified infrastructure failures are typed as unavailable, and only
                // after the enclosing transaction completes its physical rollback.
                LOGGER.warn("IAM eligibility query unavailable", outage);
                return TransactionOutcome.abstain(Result.err(IdentityVerificationFailure.UNAVAILABLE));
            }
        });
    }
}
