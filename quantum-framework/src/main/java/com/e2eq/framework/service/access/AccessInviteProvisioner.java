package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;

import java.util.List;

public interface AccessInviteProvisioner {
    default void validateScopes(String realm, String inviterUserId, List<String> scopeRefNames) {
    }

    default void onInviteAccepted(String realm, AccessInvite invite, CredentialUserIdPassword credential, UserProfile profile) {
    }
}
