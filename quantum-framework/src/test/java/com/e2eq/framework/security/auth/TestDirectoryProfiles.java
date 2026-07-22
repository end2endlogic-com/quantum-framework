package com.e2eq.framework.security.auth;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;

/**
 * Login rejects USER-typed credentials that have no directory profile in the
 * system realm (half-created accounts are unloginable by design), and
 * UserManagement.createUser stamps AccountType.USER. Tests that create users
 * through UserManagement and then log in must therefore create the directory
 * profile the same way /security/create-user does — and remove it before
 * deleting the credential so referential integrity holds.
 */
final class TestDirectoryProfiles {

    private TestDirectoryProfiles() {
    }

    static void ensure(UserProfileRepo profiles,
                       CredentialRepo credentials,
                       String systemRealm,
                       DataDomain systemDataDomain,
                       String userId,
                       String email) {
        CredentialUserIdPassword credential = credentials.findByUserId(userId, systemRealm, true)
                .orElseThrow(() -> new IllegalStateException(
                        "Credential missing for test user: " + userId));
        if (profiles.getBySubject(systemRealm, credential.getSubject()).isPresent()
                || profiles.getByUserIdWithIgnoreRules(systemRealm, userId).isPresent()) {
            return;
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setRefName(userId);
        profile.setDisplayName(userId);
        profile.setEmail(email);
        profile.setFname(userId);
        profile.setLname("user");
        profile.setStatus(UserProfile.Status.ACTIVE);
        profile.setCredentialUserIdPasswordRef(credential.createEntityReference(systemRealm));
        profile.setDataDomain(new DataDomain(
                systemDataDomain.getOrgRefName(),
                systemDataDomain.getAccountNum(),
                systemDataDomain.getTenantId(),
                systemDataDomain.getDataSegment(),
                userId));
        profiles.save(systemRealm, profile);
    }

    static void remove(UserProfileRepo profiles, String systemRealm, String userId)
            throws com.e2eq.framework.exceptions.ReferentialIntegrityViolationException {
        var profile = profiles.getByUserIdWithIgnoreRules(systemRealm, userId);
        if (profile.isPresent()) {
            profiles.delete(systemRealm, profile.get());
        }
    }
}
