package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;

/**
 * What an {@link InviteAcceptancePolicy} sees when an invite is accepted. The inviter fields are
 * read now, at acceptance, so the policy can re-run its create-time entitlement check.
 *
 * @param realm                      the registered realm the invite is for
 * @param invite                     the claimed invite (its {@code scope} is the create-time snapshot)
 * @param inviteeUserId              the invited identity (stored spelling when the account exists)
 * @param accountExists              true when the invitee already has an account
 * @param existingCredential         the invitee's credential when it exists, else null
 * @param inviterRealmAdministrator  true when the inviter still administers the realm
 * @param inviterAssignment          the inviter's current active realm assignment, or null
 */
public record InviteAcceptance(
    Realm realm,
    AccessInvite invite,
    String inviteeUserId,
    boolean accountExists,
    CredentialUserIdPassword existingCredential,
    boolean inviterRealmAdministrator,
    UserRealmRole inviterAssignment
) {
}
