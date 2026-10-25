package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AccessibleTenantResolver;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the {@link InviteAcceptancePolicy} for a realm from the realm's recorded topology: a pooled
 * realm ({@code tenancyMode = MULTI_TENANT} or {@code deploymentType = SHARED}) uses
 * {@code pooled-realm-tenant}, any other realm uses {@code dedicated-realm}. There is no per-realm
 * override. Fails closed: a missing or ambiguous policy bean, or a request/invite naming a policy
 * other than the realm's, is refused with {@code INVITE_POLICY_UNAVAILABLE}.
 */
@ApplicationScoped
public class InviteAcceptancePolicies {

    @Inject
    @Any
    Instance<InviteAcceptancePolicy> policies;

    /** True when the realm holds several tenants separated by DataDomain (MULTI_TENANT or SHARED). */
    public static boolean pooled(Realm realm) {
        return AccessibleTenantResolver.sharedMultiTenant(realm);
    }

    /** The policy for {@code realm}, decided by its tenancy mode and deployment type. */
    public InviteAcceptancePolicy forRealm(Realm realm) {
        return byId(pooled(realm) ? PooledRealmTenantInvitePolicy.ID : DedicatedRealmInvitePolicy.ID);
    }

    /** The realm's policy for a new invite; a requested id may only repeat it. */
    public InviteAcceptancePolicy forCreate(Realm realm, String requestedPolicyId) {
        InviteAcceptancePolicy policy = forRealm(realm);
        String requested = trimToNull(requestedPolicyId);
        if (requested != null && !requested.equals(policy.id())) {
            Log.warnf("access-invite.create.refused reason=policy-not-realm-policy realm=%s requested=%s",
                realm.getRefName(), sanitize(requested));
            throw unavailable("The requested acceptance policy is not available for this realm.");
        }
        return policy;
    }

    /** The policy for accepting {@code invite}: the realm's policy, which must be the one recorded on the invite. */
    public InviteAcceptancePolicy forAccept(Realm realm, AccessInvite invite) {
        InviteAcceptancePolicy policy = forRealm(realm);
        String recorded = trimToNull(invite.getAcceptancePolicy());
        if (recorded != null && !recorded.equals(policy.id())) {
            Log.warnf("access-invite.accept.refused reason=realm-policy-changed realm=%s invite=%s recorded=%s current=%s",
                realm.getRefName(), invite.getRefName(), sanitize(recorded), policy.id());
            throw unavailable("This realm's invite policy has changed since the invite was sent. Ask for a new invite.");
        }
        return policy;
    }

    InviteAcceptancePolicy byId(String id) {
        List<InviteAcceptancePolicy> matches = new ArrayList<>();
        for (InviteAcceptancePolicy candidate : policies) {
            if (id.equals(candidate.id())) {
                matches.add(candidate);
            }
        }
        if (matches.size() != 1) {
            Log.errorf("access-invite.policy.unavailable reason=%s policy=%s",
                matches.isEmpty() ? "unknown-policy" : "ambiguous-policy", sanitize(id));
            throw unavailable("The invite policy for this realm is not available.");
        }
        return matches.get(0);
    }

    private static AccessInviteException unavailable(String message) {
        return new AccessInviteException(Code.INVITE_POLICY_UNAVAILABLE, message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
