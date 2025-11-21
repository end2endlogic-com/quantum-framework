package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.util.EnvConfigUtils;
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
    EnvConfigUtils envConfigUtils;

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
        String originalPrincipal = identity.getPrincipal().getName();
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
            contextRealm = envConfigUtils.getSystemRealm();
        }

        Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(originalPrincipal, contextRealm, true);
        if (ocred.isPresent()) {
            CredentialUserIdPassword cred = ocred.get();
            // 1) Add roles from credential
            builder.addRoles(new HashSet<>(List.of(cred.getRoles())));

            // 2) Expose preferred identity (userId) as an attribute without altering the JWT principal
            String userId = cred.getUserId();
            if (userId != null && !userId.isBlank()) {
                builder.addAttribute("userId", userId);
            }

            // 3) Preserve subjectId as an attribute/claim for downstream access
            // Also keep track of the original principal value for diagnostics
            if (cred.getSubject() != null && !cred.getSubject().isBlank()) {
                builder.addAttribute("subjectId", cred.getSubject());
                builder.addAttribute("sub", cred.getSubject());
            }
            builder.addAttribute("originalPrincipal", originalPrincipal);
        } else {
            Log.warnf("Could not find user %s in realm %s to augment roles", originalPrincipal, contextRealm);
        }

        // Add user role by default if not present
        if (!identity.getRoles().contains("user")) {
            builder.addRole("user");
        }

        return builder::build;
    }
}
