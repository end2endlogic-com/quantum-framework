package com.e2eq.framework.api.system;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.security.CredentialUserIdPassword;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The flat (global) roles of the credential bound to a stable subject, and that credential's
 * userId so a caller can confirm it belongs to the principal it is resolving.
 *
 * <p>Flat credential roles apply in every realm the user can reach; realm-scoped
 * ({@code UserRealmRole}) roles are never part of this value. It carries no credential
 * material. A disabled credential ({@code activeStatus} INACTIVE or DELETED) has
 * {@code active=false} and no roles, so a caller can never carry a role for it.
 */
public record SubjectFlatRoles(String subject, String userId, boolean active, Set<String> roles) {

    public SubjectFlatRoles {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(userId, "userId");
        roles = active && roles != null ? Set.copyOf(roles) : Set.of();
    }

    /** Projection of a locally stored credential; the same rule the identity authority serves. */
    public static SubjectFlatRoles fromCredential(CredentialUserIdPassword credential) {
        ActiveStatus status = credential.getActiveStatus();
        boolean active = status != ActiveStatus.INACTIVE && status != ActiveStatus.DELETED;
        Set<String> roles = new LinkedHashSet<>();
        if (active && credential.getRoles() != null) {
            Arrays.stream(credential.getRoles())
                .filter(role -> role != null && !role.isBlank())
                .map(String::trim)
                .forEach(roles::add);
        }
        return new SubjectFlatRoles(credential.getSubject(), credential.getUserId(), active, roles);
    }
}
