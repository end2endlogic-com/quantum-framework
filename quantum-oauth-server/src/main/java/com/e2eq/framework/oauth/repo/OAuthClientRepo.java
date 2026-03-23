package com.e2eq.framework.oauth.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.oauth.model.OAuthClient;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class OAuthClientRepo extends MorphiaRepo<OAuthClient> {

    /**
     * Find an active OAuth client by its client_id in the system realm.
     */
    public Optional<OAuthClient> findByClientId(String realm, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        var ds = morphiaDataStoreWrapper.getDataStore(realm);
        var result = ds.find(OAuthClient.class)
                .filter(Filters.eq("clientId", clientId), Filters.eq("active", true))
                .first();
        return Optional.ofNullable(result);
    }
}
