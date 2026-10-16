package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.AccessInvite;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.ModifyOptions;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.Date;
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

    /**
     * Atomically consumes a pending, unexpired invite. Only one caller can win the claim for a
     * given token; a concurrent or repeated acceptance sees an empty result.
     */
    public Optional<AccessInvite> claimPending(String realm, ObjectId id, String tokenHash, Date now, String acceptedUserId) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(AccessInvite.class)
                .filter(
                    Filters.eq("_id", id),
                    Filters.eq("tokenHash", tokenHash),
                    Filters.eq("status", AccessInvite.Status.PENDING),
                    Filters.gt("expiresAt", now)
                )
                .modify(
                    new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                    UpdateOperators.set("status", AccessInvite.Status.ACCEPTED),
                    UpdateOperators.set("acceptedAt", now),
                    UpdateOperators.set("acceptedUserId", acceptedUserId)
                )
        );
    }

    /**
     * Returns a claimed invite to PENDING when provisioning failed after the claim, so the
     * legitimate invitee can retry. Only reverts the exact claim (same token, same accepted user).
     */
    public boolean releaseClaim(String realm, ObjectId id, String tokenHash, String acceptedUserId) {
        AccessInvite reverted = getMorphiaDataStoreWrapper().getDataStore(realm)
            .find(AccessInvite.class)
            .filter(
                Filters.eq("_id", id),
                Filters.eq("tokenHash", tokenHash),
                Filters.eq("status", AccessInvite.Status.ACCEPTED),
                Filters.eq("acceptedUserId", acceptedUserId)
            )
            .modify(
                new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                UpdateOperators.set("status", AccessInvite.Status.PENDING),
                UpdateOperators.unset("acceptedAt"),
                UpdateOperators.unset("acceptedUserId")
            );
        return reverted != null;
    }

    /**
     * Marks a still-pending invite EXPIRED only if it is past its expiry and still carries the
     * token that was presented, so a concurrent resend (token rotation) is never overwritten.
     */
    public boolean expirePending(String realm, ObjectId id, String tokenHash, Date now) {
        AccessInvite expired = getMorphiaDataStoreWrapper().getDataStore(realm)
            .find(AccessInvite.class)
            .filter(
                Filters.eq("_id", id),
                Filters.eq("tokenHash", tokenHash),
                Filters.eq("status", AccessInvite.Status.PENDING),
                Filters.lte("expiresAt", now)
            )
            .modify(
                new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                UpdateOperators.set("status", AccessInvite.Status.EXPIRED)
            );
        return expired != null;
    }

    /** Atomically revokes an invite that is still pending. */
    public Optional<AccessInvite> revokePending(String realm, ObjectId id) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(AccessInvite.class)
                .filter(Filters.eq("_id", id), Filters.eq("status", AccessInvite.Status.PENDING))
                .modify(
                    new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                    UpdateOperators.set("status", AccessInvite.Status.REVOKED)
                )
        );
    }

    /**
     * Atomically replaces the token of a pending invite (resend). The previous token stops
     * working as soon as this returns.
     */
    public Optional<AccessInvite> rotatePendingToken(String realm, ObjectId id, String expectedTokenHash,
                                                     String newTokenHash, String newTokenHint, Date newExpiresAt) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(AccessInvite.class)
                .filter(
                    Filters.eq("_id", id),
                    Filters.eq("tokenHash", expectedTokenHash),
                    Filters.eq("status", AccessInvite.Status.PENDING)
                )
                .modify(
                    new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                    UpdateOperators.set("tokenHash", newTokenHash),
                    UpdateOperators.set("tokenHint", newTokenHint),
                    UpdateOperators.set("expiresAt", newExpiresAt)
                )
        );
    }
}
