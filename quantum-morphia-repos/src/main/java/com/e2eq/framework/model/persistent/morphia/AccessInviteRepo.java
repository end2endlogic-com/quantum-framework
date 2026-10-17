package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.ModifyOptions;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperator;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link AccessInvite}. Every state or token change after creation goes through a
 * conditional {@code findOneAndUpdate} here, never a load-modify-save, and every such transition
 * increments the Morphia {@code @Version} and stamps {@code auditInfo.lastUpdate*}. A copy of the
 * invite loaded before a transition therefore carries a stale version, and a later full
 * {@code save} of it fails with a version mismatch instead of overwriting the transition.
 */
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
     * given token; a concurrent or repeated acceptance sees an empty result. Matches on state and
     * token (not version) so a concurrent resend that only staged a replacement token does not
     * refuse an acceptance of the still-current token; any staged token is discarded.
     */
    public Optional<AccessInvite> claimPending(String realm, ObjectId id, String tokenHash, Date now, String acceptedUserId) {
        return transition(realm,
            new Filter[]{
                Filters.eq("_id", id),
                Filters.eq("tokenHash", tokenHash),
                Filters.eq("status", AccessInvite.Status.PENDING),
                Filters.gt("expiresAt", now)
            },
            UpdateOperators.set("status", AccessInvite.Status.ACCEPTED),
            UpdateOperators.set("acceptedAt", now),
            UpdateOperators.set("acceptedUserId", acceptedUserId),
            UpdateOperators.unset("pendingTokenHash"),
            UpdateOperators.unset("pendingTokenHint"),
            UpdateOperators.unset("pendingExpiresAt"),
            UpdateOperators.unset("pendingStagedAt"));
    }

    /**
     * Returns a claimed invite to PENDING when provisioning failed after the claim, so the
     * legitimate invitee can retry. Only reverts the exact claim: same token, same accepted user,
     * and the version the claim produced (nothing has changed the invite since).
     */
    public boolean releaseClaim(String realm, ObjectId id, Long claimedVersion, String tokenHash, String acceptedUserId) {
        return transition(realm,
            new Filter[]{
                Filters.eq("_id", id),
                Filters.eq("version", claimedVersion),
                Filters.eq("tokenHash", tokenHash),
                Filters.eq("status", AccessInvite.Status.ACCEPTED),
                Filters.eq("acceptedUserId", acceptedUserId)
            },
            UpdateOperators.set("status", AccessInvite.Status.PENDING),
            UpdateOperators.unset("acceptedAt"),
            UpdateOperators.unset("acceptedUserId")).isPresent();
    }

    /**
     * Marks a still-pending invite EXPIRED only if it is past its expiry, still carries the token
     * that was presented, and has no resend in flight, so a concurrent resend is never overwritten
     * or stranded.
     */
    public boolean expirePending(String realm, ObjectId id, String tokenHash, Date now) {
        return transition(realm,
            new Filter[]{
                Filters.eq("_id", id),
                Filters.eq("tokenHash", tokenHash),
                Filters.eq("status", AccessInvite.Status.PENDING),
                Filters.lte("expiresAt", now),
                // A resend in flight renews the expiry when it promotes; the acceptance is still
                // refused as expired, but the record is left for the resend to complete.
                Filters.eq("pendingTokenHash", null)
            },
            UpdateOperators.set("status", AccessInvite.Status.EXPIRED)).isPresent();
    }

    /**
     * Atomically revokes an invite that is still pending. Matches on state only: a revocation must
     * win over a concurrent resend rather than be refused by it. Any staged token is discarded.
     */
    public Optional<AccessInvite> revokePending(String realm, ObjectId id) {
        return transition(realm,
            new Filter[]{Filters.eq("_id", id), Filters.eq("status", AccessInvite.Status.PENDING)},
            UpdateOperators.set("status", AccessInvite.Status.REVOKED),
            UpdateOperators.unset("pendingTokenHash"),
            UpdateOperators.unset("pendingTokenHint"),
            UpdateOperators.unset("pendingExpiresAt"),
            UpdateOperators.unset("pendingStagedAt"));
    }

    /**
     * First step of a resend: stages a replacement token next to the current one. The current
     * token keeps working; the staged one cannot be used until {@link #promoteStagedToken}.
     * Refused (empty) while another resend holds an unexpired staging lease, so two concurrent
     * resends never both deliver a token; a lease older than {@code leaseStaleBefore} (a resend
     * that crashed mid-delivery) is taken over.
     */
    public Optional<AccessInvite> stagePendingToken(String realm, ObjectId id, String currentTokenHash,
                                                    String newTokenHash, String newTokenHint, Date newExpiresAt,
                                                    Date stagedAt, Date leaseStaleBefore) {
        return transition(realm,
            new Filter[]{
                Filters.eq("_id", id),
                Filters.eq("tokenHash", currentTokenHash),
                Filters.eq("status", AccessInvite.Status.PENDING),
                Filters.or(
                    Filters.eq("pendingTokenHash", null),
                    Filters.lt("pendingStagedAt", leaseStaleBefore)
                )
            },
            UpdateOperators.set("pendingTokenHash", newTokenHash),
            UpdateOperators.set("pendingTokenHint", newTokenHint),
            UpdateOperators.set("pendingExpiresAt", newExpiresAt),
            UpdateOperators.set("pendingStagedAt", stagedAt));
    }

    /**
     * Second step of a resend, after the staged token was delivered: makes it the invite's token
     * (the previous token stops working) only if the invite is exactly as staged, i.e. at the
     * staged version, still pending, and still carrying this staged token.
     */
    public Optional<AccessInvite> promoteStagedToken(String realm, ObjectId id, Long stagedVersion,
                                                     String stagedTokenHash, String stagedTokenHint, Date stagedExpiresAt) {
        return transition(realm,
            new Filter[]{
                Filters.eq("_id", id),
                Filters.eq("version", stagedVersion),
                Filters.eq("status", AccessInvite.Status.PENDING),
                Filters.eq("pendingTokenHash", stagedTokenHash)
            },
            UpdateOperators.set("tokenHash", stagedTokenHash),
            UpdateOperators.set("tokenHint", stagedTokenHint),
            UpdateOperators.set("expiresAt", stagedExpiresAt),
            UpdateOperators.unset("pendingTokenHash"),
            UpdateOperators.unset("pendingTokenHint"),
            UpdateOperators.unset("pendingExpiresAt"),
            UpdateOperators.unset("pendingStagedAt"));
    }

    /** Drops a staged token whose delivery failed; the current token is untouched. */
    public boolean discardStagedToken(String realm, ObjectId id, String stagedTokenHash) {
        return transition(realm,
            new Filter[]{Filters.eq("_id", id), Filters.eq("pendingTokenHash", stagedTokenHash)},
            UpdateOperators.unset("pendingTokenHash"),
            UpdateOperators.unset("pendingTokenHint"),
            UpdateOperators.unset("pendingExpiresAt"),
            UpdateOperators.unset("pendingStagedAt")).isPresent();
    }

    /**
     * Applies one conditional transition, adding the version increment and the audit stamp the
     * framework applies on every update ({@code AuditInterceptor} identity convention). Morphia 3
     * adds a {@code $inc} of the {@code @Version} field to an update that names none; the
     * increment is stated here so the optimistic-lock guarantee does not rest on that default.
     */
    private Optional<AccessInvite> transition(String realm, Filter[] filters, UpdateOperator... changes) {
        List<UpdateOperator> ops = new ArrayList<>(Arrays.asList(changes));
        ops.add(UpdateOperators.inc("version", 1L));
        ops.addAll(auditUpdate());
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(AccessInvite.class)
                .filter(filters)
                .modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                    ops.get(0), ops.subList(1, ops.size()).toArray(new UpdateOperator[0]))
        );
    }

    private static List<UpdateOperator> auditUpdate() {
        List<UpdateOperator> ops = new ArrayList<>();
        Optional<PrincipalContext> principal = SecurityContext.getPrincipalContext();
        ops.add(UpdateOperators.set("auditInfo.lastUpdateTs", new Date()));
        ops.add(UpdateOperators.set("auditInfo.lastUpdateIdentity", principal.map(PrincipalContext::getUserId).orElse("ANONYMOUS")));
        principal.filter(p -> p.getImpersonatedBySubject() != null || p.getImpersonatedByUserId() != null).ifPresent(p -> {
            ops.add(UpdateOperators.set("auditInfo.impersonatorSubject", p.getImpersonatedBySubject()));
            ops.add(UpdateOperators.set("auditInfo.impersonatorUserId", p.getImpersonatedByUserId()));
            ops.add(UpdateOperators.set("auditInfo.actingOnBehalfOfUserId", p.getActingOnBehalfOfUserId()));
            ops.add(UpdateOperators.set("auditInfo.actingOnBehalfOfSubject", p.getActingOnBehalfOfSubject()));
        });
        return ops;
    }
}
