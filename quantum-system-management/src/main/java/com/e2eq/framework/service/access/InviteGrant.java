package com.e2eq.framework.service.access;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * What accepting an invite grants, as decided by its {@link InviteAcceptancePolicy}. The service
 * applies it through the realm-scoped writes (union merge into the invitee's single
 * {@code UserRealmRole} for the realm, explicit realm entry, rollback on failure).
 *
 * <p>The service refuses a grant that names roles or applications the invite does not carry, or
 * (in a pooled realm) a tenant that is not an active member of the realm.</p>
 */
@Value
@Builder
public class InviteGrant {

    /** Home context of a credential created by this acceptance (required when no account exists). */
    DomainContext homeContext;

    /** Data domain of a newly created {@code UserRealmRole} (the user's default partition in the realm). */
    DataDomain assignmentDataDomain;

    /** {@code authorizedTenantIds} of a newly created assignment. */
    List<String> newAssignmentTenantIds;

    /** Tenants unioned into the assignment's {@code authorizedTenantIds}, new or existing. */
    List<String> addTenantIds;

    /** Provenance: the org that sponsored the invitee (set on a new assignment). */
    String sponsoringOrgRefName;

    /** Realm-scoped roles merged into the assignment. */
    List<String> roles;

    /** Applications merged into the assignment's {@code authorizedApplications}. */
    List<String> authorizedApplications;

    /** Data domain of a user profile created by this acceptance; null for the framework default. */
    DataDomain profileDataDomain;

    /** The tenant the invitee is placed in (reported to the caller). */
    String tenantId;
}
