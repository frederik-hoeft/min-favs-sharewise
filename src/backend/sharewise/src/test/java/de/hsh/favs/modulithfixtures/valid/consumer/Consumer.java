package de.hsh.favs.modulithfixtures.valid.consumer;

import de.hsh.favs.modulithfixtures.valid.provider.ProviderApi;
import de.hsh.favs.modulithfixtures.valid.shared.infra.di.SharedContract;
import de.hsh.favs.modulithfixtures.valid.shared.infra.web.SharedWebContract;
import de.hsh.favs.modulithfixtures.valid.shared.result.SharedResult;

public final class Consumer {

    private ProviderApi provider;

    private SharedContract sharedContract;

    private SharedResult sharedResult;

    private SharedWebContract sharedWebContract;
}
