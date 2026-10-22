package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-only provisioner that fails acceptance of invites whose message is {@link #FAIL_MARKER}, so
 * tests can prove a failed acceptance rolls its realm grant back. Inert for every other invite.
 */
@ApplicationScoped
public class FailingTestInviteProvisioner implements AccessInviteProvisioner {

    public static final String FAIL_MARKER = "test:fail-provisioning";

    @Override
    public void onInviteAccepted(String realm, AccessInvite invite, CredentialUserIdPassword credential, UserProfile profile) {
        if (FAIL_MARKER.equals(invite.getInviteMessage())) {
            throw new IllegalStateException("simulated provisioning failure");
        }
    }
}
