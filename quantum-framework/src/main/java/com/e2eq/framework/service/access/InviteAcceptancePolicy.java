package com.e2eq.framework.service.access;

import com.e2eq.framework.model.persistent.base.DataDomain;

/**
 * Decides who an access invite may place where, and what accepting it grants.
 *
 * <p>Implementations are CDI beans identified by {@link #id()}. The policy for an invite follows
 * from its realm's topology ({@link InviteAcceptancePolicies#forRealm}): {@code pooled-realm-tenant}
 * for a pooled realm (MULTI_TENANT or SHARED), {@code dedicated-realm} otherwise. The policy id is
 * recorded on the invite and must still be the realm's policy when the invite is accepted.</p>
 *
 * <p>Order of operations:</p>
 * <ol>
 *   <li>create: realm access, grantable roles and applications are checked by the service; then
 *       {@link #validateCreate}; then every {@link AccessInviteProvisioner#validateInviteCreation}
 *       and {@link AccessInviteProvisioner#validateScopes}; then the invite is saved.</li>
 *   <li>accept: identity binding and the inviter's realm/role re-check run first; the invite is
 *       claimed; then {@link #resolveGrant} (which must re-run the create-time entitlement check);
 *       the grant is applied (credential, realm assignment, profile); then every
 *       {@link AccessInviteProvisioner#onInviteAccepted}. If anything throws, everything the
 *       acceptance wrote is rolled back and the invite stays pending.</li>
 * </ol>
 *
 * <p>Both calls must fail closed with a typed {@link AccessInviteException}; they never fall back
 * to default fields.</p>
 */
public interface InviteAcceptancePolicy {

    /** Stable id this policy is selected by (for example {@code pooled-realm-tenant}). */
    String id();

    /**
     * Checks, when the invite is created, that the inviter may invite into the snapshotted scope.
     * {@code creation.scope()} is the inviter's resolved partition from its verified principal.
     */
    void validateCreate(InviteCreation creation);

    /**
     * Re-checks the inviter's entitlement against the invite's recorded scope and returns what
     * acceptance grants the invitee. Runs after the invite is claimed; a throw releases the claim.
     */
    InviteGrant resolveGrant(InviteAcceptance acceptance);

    /**
     * The data domain to store the invite record itself under, or {@code null} for the framework
     * default (the realm-wide default domain owned by the inviter).
     */
    default DataDomain inviteRecordDataDomain(InviteCreation creation) {
        return null;
    }
}
