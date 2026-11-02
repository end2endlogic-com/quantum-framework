package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.rest.requests.AccessInviteRequest;

import java.util.List;

public interface AccessInviteProvisioner {
    default void validateInviteCreation(String realm, String inviterUserId, AccessInviteRequest request) {
    }

    default void validateScopes(String realm, String inviterUserId, List<String> scopeRefNames) {
    }

    /**
     * Called once the invite has been claimed and its realm grant written. If this (or any later
     * step) throws, the realm grant is rolled back, the claim is released, and the invitee may
     * retry, so implementations must be idempotent: a retry can run this again for the same invite
     * and user.
     */
    default void onInviteAccepted(String realm, AccessInvite invite, CredentialUserIdPassword credential, UserProfile profile) {
    }
}
