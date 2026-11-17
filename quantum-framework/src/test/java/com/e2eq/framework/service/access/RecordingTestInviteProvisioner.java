package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only provisioner that records, per accepted invite, the security context the acceptance's
 * system-scope work runs in, so tests can assert which application authorizes it. Inert otherwise.
 */
@ApplicationScoped
public class RecordingTestInviteProvisioner implements AccessInviteProvisioner {

    /** The principal and resource application ids seen while an invite was being accepted. */
    public record Seen(String userId, String principalApplicationId, String resourceApplicationId) {
    }

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    @Override
    public void onInviteAccepted(String realm, AccessInvite invite, CredentialUserIdPassword credential, UserProfile profile) {
        PrincipalContext principal = SecurityContext.getPrincipalContext().orElse(null);
        ResourceContext resource = SecurityContext.getResourceContext().orElse(null);
        seen.put(invite.getRefName(), new Seen(
            principal == null ? null : principal.getUserId(),
            principal == null ? null : principal.getApplicationId(),
            resource == null ? null : resource.getApplicationId()));
    }

    public Seen seenFor(String inviteRefName) {
        return seen.get(inviteRefName);
    }
}
