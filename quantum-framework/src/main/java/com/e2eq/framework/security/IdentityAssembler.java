package com.e2eq.framework.security;

import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Central place to build canonical SecurityIdentity instances used by the framework.
 * Contract:
 *  - principal.getName() = userId
 *  - attribute "idp-sub" = IdP subject
 *  - roles = union of provider token roles + credential roles + user group roles
 */
@ApplicationScoped
public class IdentityAssembler {

    public static final String ATTR_IDP_SUBJECT = "idp-sub";

    @Inject
    CredentialRepo credentialRepo;
    @Inject
    UserProfileRepo userProfileRepo;
    @Inject
    UserGroupRepo userGroupRepo;
    @Inject
    EnvConfigUtils envConfigUtils;

    public SecurityIdentity assemble(ProviderClaims claims) {
        Objects.requireNonNull(claims, "claims cannot be null");
        return assemble(claims.getSubject(), claims.getTokenRoles(), claims.getAttributes());
    }

    public SecurityIdentity assemble(String subject, Set<String> tokenRoles, Map<String, Object> attrs) {
        Objects.requireNonNull(subject, "subject cannot be null");

        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<CredentialUserIdPassword> ocreds = Optional.empty();
        try {
            ocreds = credentialRepo.findBySubject(subject, systemRealm, true);
        } catch (Exception e) {
            Log.warnf(e, "Failed to lookup credential by subject %s in realm %s", subject, systemRealm);
        }

        if (ocreds.isEmpty()) {
            // As a compatibility fallback, allow a subject that is actually a userId
            try {
                ocreds = credentialRepo.findByUserId(subject, systemRealm, true);
            } catch (Exception e) {
                Log.warnf(e, "Fallback userId lookup failed for subject=%s", subject);
            }
        }

        String userId;
        Set<String> mergedRoles = new LinkedHashSet<>();
        if (tokenRoles != null) mergedRoles.addAll(tokenRoles);

        if (ocreds.isPresent()) {
            var cred = ocreds.get();
            userId = cred.getUserId();
            // merge credential roles
            if (cred.getRoles() != null) {
                for (String r : cred.getRoles()) if (r != null && !r.isBlank()) mergedRoles.add(r);
            }
            // merge user group roles via profile
            try {
                var userProfileOpt = userProfileRepo.getBySubject(cred.getSubject());
                if (userProfileOpt.isPresent()) {
                    var groups = userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                    if (groups != null) {
                        for (UserGroup g : groups) {
                            if (g != null && g.getRoles() != null) {
                                for (String r : g.getRoles()) if (r != null && !r.isBlank()) mergedRoles.add(r);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.warn("Failed to expand roles via user groups; continuing with token/credential roles", e);
            }
        } else {
            userId = subject; // last resort; ensures principal is set; logs to highlight mismatch
            Log.warnf("No credential found for subject=%s; using subject as userId for principal", subject);
        }

        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
        builder.setPrincipal(() -> userId);
        for (String r : mergedRoles) builder.addRole(r);

        // attributes
        builder.addAttribute(ATTR_IDP_SUBJECT, subject);
        if (attrs != null) {
            for (Map.Entry<String, Object> e : attrs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) builder.addAttribute(e.getKey(), e.getValue());
            }
        }
        builder.addAttribute("token_type", "assembled");
        builder.addAttribute("auth_time", System.currentTimeMillis());

        return builder.build();
    }
}
