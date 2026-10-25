package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.InviteScope;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.rest.requests.AccessInviteRequest;

/**
 * What an {@link InviteAcceptancePolicy} sees when an invite is created.
 *
 * @param realm                 the registered realm the invite is for
 * @param inviter               the inviter's verified principal context
 * @param inviterUserId         the inviter's userId (normalized)
 * @param systemPrincipal       true when the inviter is the framework system principal
 * @param realmAdministrator    true when the inviter administers the realm (or is the system principal)
 * @param inviterAssignment     the inviter's active realm assignment for the realm, or null
 * @param scope                 the inviter's resolved partition (never from the request body)
 * @param request               the invite request (read-only; identity and scope come from elsewhere)
 */
public record InviteCreation(
    Realm realm,
    PrincipalContext inviter,
    String inviterUserId,
    boolean systemPrincipal,
    boolean realmAdministrator,
    UserRealmRole inviterAssignment,
    InviteScope scope,
    AccessInviteRequest request
) {
}
