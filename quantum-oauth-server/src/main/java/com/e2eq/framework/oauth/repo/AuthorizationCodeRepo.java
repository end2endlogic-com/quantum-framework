package com.e2eq.framework.oauth.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.oauth.model.AuthorizationCode;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;
import java.util.Optional;

@ApplicationScoped
public class AuthorizationCodeRepo extends MorphiaRepo<AuthorizationCode> {

    /**
     * Find a valid (non-consumed, non-expired) authorization code.
     */
    public Optional<AuthorizationCode> findValidCode(String realm, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        var ds = morphiaDataStoreWrapper.getDataStore(realm);
        var result = ds.find(AuthorizationCode.class)
                .filter(
                        Filters.eq("code", code),
                        Filters.eq("consumed", false),
                        Filters.gt("expiresAt", new Date()))
                .first();
        return Optional.ofNullable(result);
    }

    /**
     * Mark an authorization code as consumed so it cannot be reused.
     */
    public boolean consumeCode(String realm, String code) {
        var ds = morphiaDataStoreWrapper.getDataStore(realm);
        var result = ds.find(AuthorizationCode.class)
                .filter(Filters.eq("code", code), Filters.eq("consumed", false))
                .update(UpdateOperators.set("consumed", true));
        return result.getModifiedCount() > 0;
    }
}
