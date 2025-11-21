package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

/**
 * Normalizes any incoming SecurityIdentity to the canonical framework contract:
 *  - principal.getName() must be userId
 *  - attribute "idp-sub" should carry the IdP subject
 * Roles present on the identity are preserved.
 */
@ApplicationScoped
public class SecurityIdentityNormalizer {

    @Inject
    CredentialRepo credentialRepo;

    public SecurityIdentity normalize(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) return identity;

        String principal = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (principal == null || principal.isBlank()) return identity; // nothing to do

        String idpSub = safeString(identity.getAttribute(IdentityAssembler.ATTR_IDP_SUBJECT));

        try {
            // First, try by userId
            Optional<CredentialUserIdPassword> byUserId = credentialRepo.findByUserId(principal);
            if (byUserId.isPresent()) {
                String userId = byUserId.get().getUserId();
                String subject = (idpSub != null) ? idpSub : byUserId.get().getSubject();
                return rebuild(identity, userId, subject);
            }

            // Next, treat principal as subject and resolve userId
            Optional<CredentialUserIdPassword> bySubject = credentialRepo.findBySubject(principal);
            if (bySubject.isPresent()) {
                String userId = bySubject.get().getUserId();
                String subject = bySubject.get().getSubject();
                Log.warnf("Normalizing identity where principal looked like subject. userId=%s subject=%s", userId, subject);
                return rebuild(identity, userId, subject);
            }
        } catch (Exception e) {
            Log.warn("Failed to normalize SecurityIdentity; returning original", e);
        }

        return identity; // fallback
    }

    private SecurityIdentity rebuild(SecurityIdentity original, String userId, String subject) {
        QuarkusSecurityIdentity.Builder b = QuarkusSecurityIdentity.builder(original);
        b.setPrincipal(() -> userId);
        // ensure attribute present
        if (subject != null) b.addAttribute(IdentityAssembler.ATTR_IDP_SUBJECT, subject);
        // roles are already on the builder(original) copy; nothing extra needed
        return b.build();
    }

    private static String safeString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
}
