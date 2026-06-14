package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.AccessInvite;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AccessInviteRepo extends MorphiaRepo<AccessInvite> {

    public List<AccessInvite> getAllListIgnoreRules(String realm) {
        dev.morphia.Datastore datastore = getMorphiaDataStoreWrapper().getDataStore(realm);
        try (dev.morphia.query.MorphiaCursor<AccessInvite> cursor = datastore.find(AccessInvite.class).iterator()) {
            return cursor.toList();
        }
    }

    public Optional<AccessInvite> findByRefNameIgnoreRules(String realm, String refName) {
        return findByRefName(getMorphiaDataStoreWrapper().getDataStore(realm), refName, true);
    }

    public Optional<AccessInvite> findByTokenHash(String realm, String tokenHash) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(AccessInvite.class)
                .filter(Filters.eq("tokenHash", tokenHash))
                .first()
        );
    }

    public List<AccessInvite> findByEmail(String realm, String email) {
        return getMorphiaDataStoreWrapper().getDataStore(realm)
            .find(AccessInvite.class)
            .filter(Filters.eq("email", email))
            .iterator()
            .toList();
    }

    public List<AccessInvite> findByTargetUserId(String realm, String targetUserId) {
        return getMorphiaDataStoreWrapper().getDataStore(realm)
            .find(AccessInvite.class)
            .filter(Filters.eq("targetUserId", targetUserId))
            .iterator()
            .toList();
    }
}
