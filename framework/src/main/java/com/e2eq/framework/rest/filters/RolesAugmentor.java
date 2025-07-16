package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@ApplicationScoped
public class RolesAugmentor implements SecurityIdentityAugmentor {
    @Inject
    CredentialRepo credentialRepo;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    HttpHeaders httpHeaders;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        return Uni.createFrom().item(build(identity));
    }

    private Supplier<SecurityIdentity> build(SecurityIdentity identity) {
        if (identity.isAnonymous()) {
            return () -> identity;
        }

        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);
        String principal = identity.getPrincipal().getName();
        String contextRealm = null;

        // Check if HTTP request context is active
        try {
            contextRealm = httpHeaders.getHeaderString("X-Realm");
            Log.debugf("RoleAugmentor overriding realm with ContextRealm: %s", contextRealm);
        } catch (ContextNotActiveException e) {
            Log.debug("No HTTP request context available, falling back to default realm");
        }

        // Fallback to system realm if contextRealm is not set
        if (contextRealm == null) {
            contextRealm = securityUtils.getSystemRealm();
        }

        Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUsername(principal, contextRealm, true);
        if (ocred.isPresent()) {
            builder.addRoles(new HashSet<>(List.of(ocred.get().getRoles())));
        } else {
            Log.warnf("Could not find user %s in realm %s to augment roles", principal, contextRealm);
        }

        // Add user role by default if not present
        if (!identity.getRoles().contains("user")) {
            builder.addRole("user");
        }

        return builder::build;
    }
}
