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
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Selects the {@link InviteAcceptancePolicy} for a realm. Fails closed: an unknown or ambiguous id,
 * a pooled realm without a configured policy, or a request/invite naming a policy other than the
 * realm's is refused with {@code INVITE_POLICY_UNAVAILABLE}.
 *
 * <p>The realm's policy id comes from {@code Realm.inviteAcceptancePolicy}; when that is unset, from
 * the application property {@code quantum.access-invites.realm-policy."<realm>"} (useful when the
 * realm catalog is owned by a remote control plane). When both are set they must agree.</p>
 */
@ApplicationScoped
public class InviteAcceptancePolicies {

    static final String REALM_POLICY_PROPERTY = "quantum.access-invites.realm-policy";

    @Inject
    @Any
    Instance<InviteAcceptancePolicy> policies;

    @Inject
    Config config;

    /** True when the realm holds several tenants separated by DataDomain (MULTI_TENANT or SHARED). */
    public static boolean pooled(Realm realm) {
        return AccessibleTenantResolver.sharedMultiTenant(realm);
    }

    /** The policy configured for {@code realm}. */
    public InviteAcceptancePolicy forRealm(Realm realm) {
        String configured = configuredPolicyId(realm);
        if (configured == null) {
            if (pooled(realm)) {
                Log.warnf("access-invite.policy.unavailable reason=pooled-realm-without-policy realm=%s", realm.getRefName());
                throw unavailable("Invites are not configured for this realm.");
            }
            configured = DedicatedRealmInvitePolicy.ID;
        }
        return byId(configured);
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
            throw unavailable("The invite policy configured for this realm is not available.");
        }
        return matches.get(0);
    }

    private String configuredPolicyId(Realm realm) {
        String fromRealm = trimToNull(realm.getInviteAcceptancePolicy());
        String fromConfig = configuredForRealmName(realm.getRefName());
        if (fromRealm != null && fromConfig != null && !fromRealm.equals(fromConfig)) {
            Log.errorf("access-invite.policy.unavailable reason=conflicting-configuration realm=%s realmRecord=%s property=%s",
                realm.getRefName(), sanitize(fromRealm), sanitize(fromConfig));
            throw unavailable("The invite policy configured for this realm is not available.");
        }
        return fromRealm != null ? fromRealm : fromConfig;
    }

    private String configuredForRealmName(String realmRefName) {
        if (realmRefName == null || config == null) {
            return null;
        }
        Optional<String> quoted = config.getOptionalValue(REALM_POLICY_PROPERTY + ".\"" + realmRefName + "\"", String.class);
        if (quoted.isPresent()) {
            return trimToNull(quoted.get());
        }
        if (realmRefName.indexOf('.') < 0) {
            return config.getOptionalValue(REALM_POLICY_PROPERTY + "." + realmRefName, String.class)
                .map(InviteAcceptancePolicies::trimToNull).orElse(null);
        }
        return null;
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
