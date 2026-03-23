package com.e2eq.framework.model.persistent.morphia;

import static dev.morphia.query.filters.Filters.*;

import com.e2eq.framework.model.persistent.base.ShareLink;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class ShareLinkRepo extends MorphiaRepo<ShareLink> {

    public Optional<ShareLink> findByPublicId(String publicId) {
        var ds = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
        var q = ds.find(ShareLink.class).filter(eq("publicId", publicId));
        return Optional.ofNullable(q.first());
    }

    public boolean revokeByPublicId(String publicId) {
        Optional<ShareLink> o = findByPublicId(publicId);
        if (o.isEmpty()) return false;
        ShareLink link = o.get();
        link.setStatus(ShareLink.Status.REVOKED);
        link.setLastUpdatedAt(Instant.now());
        save(link);
        return true;
    }

    public boolean tryConsumeOneUse(ShareLink link) {
        try (MorphiaSession session = morphiaDataStoreWrapper
                .getDataStore(getSecurityContextRealmId())
                .startSession()) {
            ShareLink fresh = session.find(ShareLink.class)
                    .filter(eq("_id", link.getId()))
                    .first();
            if (fresh == null) return false;
            Instant now = Instant.now();
            if (fresh.getStatus() != ShareLink.Status.ACTIVE) return false;
            if (fresh.getExpiresAt() != null && now.isAfter(fresh.getExpiresAt())) return false;
            if (fresh.getMaxUses() != null
                    && fresh.getUsedCount() != null
                    && fresh.getUsedCount() >= fresh.getMaxUses()) return false;

            long used = fresh.getUsedCount() == null ? 0L : fresh.getUsedCount();
            fresh.setUsedCount(used + 1);
            fresh.setLastAccessedAt(now);
            fresh.setLastUpdatedAt(now);
            super.save(session, fresh);
            return true;
        }
    }
}
