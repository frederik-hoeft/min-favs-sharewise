package de.hsh.favs.sharewise.identity.internal.profile;

import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authenticated-public name resolution; does not consult other domains. */
public final class ProfileLookupService {
    private final IdentityContext identity;
    private final Transactions transactions;
    private final AccountProfileStore profiles;
    public ProfileLookupService(IdentityContext identity, Transactions transactions, AccountProfileStore profiles) {
        this.identity = identity;
        this.transactions = transactions;
        this.profiles = profiles;
    }
    public List<PublicProfile> lookup(ProfileLookup command) {
        identity.requireUserId();
        if (command.userIds().isEmpty()) return List.of();
        return transactions.execute(() -> {
            Map<UUID, PublicProfile> found = new HashMap<>();
            for (PublicProfile profile : profiles.lookup(command.userIds())) found.put(profile.userId(), profile);
            return TransactionOutcome.abstain(command.userIds().stream().filter(found::containsKey).map(found::get).toList());
        });
    }
}
