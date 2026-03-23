package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class RepoSecurityContextResolver {

    private final SecurityIdentity securityIdentity;
    private final CredentialRepo credentialRepo;
    private final EnvConfigUtils envConfigUtils;
    private final RuleContext ruleContext;
    private final String defaultRealm;

    RepoSecurityContextResolver(
            SecurityIdentity securityIdentity,
            CredentialRepo credentialRepo,
            EnvConfigUtils envConfigUtils,
            RuleContext ruleContext,
            String defaultRealm) {
        this.securityIdentity = securityIdentity;
        this.credentialRepo = credentialRepo;
        this.envConfigUtils = envConfigUtils;
        this.ruleContext = ruleContext;
        this.defaultRealm = defaultRealm;
    }

    void ensureSecurityContextFromIdentity() {
        String currentIdentity = null;
        var principalContext = SecurityContext.getPrincipalContext();
        if (principalContext.isPresent()) {
            Log.debugf("ensureSecurityContextFromIdentity: security context present, identity:%s", principalContext.get().getUserId());
            currentIdentity = principalContext.get().getUserId();
        }

        boolean hasIdentity = securityIdentity != null && !securityIdentity.isAnonymous();
        if (hasIdentity) {
            Log.debugf("non anonymous identity:%s", securityIdentity.getPrincipal().getName());
        }

        String identityName = null;
        if (hasIdentity && securityIdentity.getPrincipal() != null) {
            identityName = securityIdentity.getPrincipal().getName();
        }

        boolean needRebuild = false;
        if (principalContext.isEmpty()) {
            needRebuild = hasIdentity;
        } else if (hasIdentity && identityName != null && !identityName.equals(currentIdentity)) {
            needRebuild = true;
        }

        if (!needRebuild) {
            if (SecurityContext.getResourceContext().isEmpty()) {
                SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
            }
            return;
        }

        Log.debug("rebuilding identity because identityName is null or does not equal current identity");
        String principalName = identityName != null ? identityName : envConfigUtils.getAnonymousUserId();
        Set<String> rolesSet = securityIdentity != null && securityIdentity.getRoles() != null
                ? securityIdentity.getRoles()
                : Collections.emptySet();
        String[] roles = rolesSet.isEmpty() ? new String[0] : rolesSet.toArray(new String[0]);

        Log.debugf("Attempting to locate user using userId:%s", principalName);
        Optional<CredentialUserIdPassword> credentials = credentialRepo.findByUserId(principalName, envConfigUtils.getSystemRealm(), true);
        if (credentials.isEmpty()) {
            credentials = credentialRepo.findBySubject(principalName, envConfigUtils.getSystemRealm(), true);
        }

        DataDomain dataDomain;
        String userId;
        String contextRealm;
        Map<String, String> area2RealmOverrides = null;
        com.e2eq.framework.model.security.DomainContext domainContext = null;
        com.e2eq.framework.model.security.DataDomainPolicy dataDomainPolicy = null;
        if (credentials.isPresent()) {
            CredentialUserIdPassword creds = credentials.get();
            dataDomain = creds.getDomainContext().toDataDomain(creds.getUserId());
            userId = creds.getUserId();
            contextRealm = creds.getDomainContext().getDefaultRealm();
            domainContext = creds.getDomainContext();
            dataDomainPolicy = creds.getDataDomainPolicy();
            String[] credRoles = creds.getRoles();
            area2RealmOverrides = creds.getArea2RealmOverrides();
            if (credRoles != null && credRoles.length > 0) {
                Set<String> combined = new HashSet<>(rolesSet);
                combined.addAll(Arrays.asList(credRoles));
                roles = combined.toArray(new String[0]);
            }
        } else {
            Log.warnf("Unable to locate credentials for userId:%s in realm:%s. Falling back to identity defaults.", principalName, envConfigUtils.getSystemRealm());
            dataDomain = new DataDomain();
            dataDomain.setOwnerId(principalName);
            dataDomain.setTenantId(envConfigUtils.getSystemTenantId());
            dataDomain.setOrgRefName(envConfigUtils.getSystemOrgRefName());
            dataDomain.setAccountNum(envConfigUtils.getSystemAccountNumber());
            dataDomain.setDataSegment(0);

            userId = principalName;
            contextRealm = envConfigUtils.getSystemRealm();
        }

        PrincipalContext rebuiltContext = new PrincipalContext.Builder()
                .withDefaultRealm(contextRealm)
                .withDataDomain(dataDomain)
                .withUserId(userId)
                .withRoles(roles)
                .withArea2RealmOverrides(area2RealmOverrides)
                .withDomainContext(domainContext)
                .withDataDomainPolicy(dataDomainPolicy)
                .withScope("AUTHENTICATED")
                .build();
        SecurityContext.setPrincipalContext(rebuiltContext);

        if (SecurityContext.getResourceContext().isEmpty()) {
            SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        }
        if (Log.isDebugEnabled()) {
            Log.debugf("[MorphiaRepo] Principal Context %s from SecurityIdentity for user %s, roles=%s",
                    principalContext.isEmpty() ? "built" : "rebuilt", userId, Arrays.toString(roles));
        }
    }

    String getSecurityContextRealmId() {
        if (SecurityContext.getResourceContext().isEmpty() || SecurityContext.getPrincipalContext().isEmpty()) {
            ensureSecurityContextFromIdentity();
        }

        String realmId = defaultRealm;
        if (SecurityContext.getPrincipalContext().isPresent()) {
            ResourceContext resourceContext = SecurityContext.getResourceContext()
                    .orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
            realmId = ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(), resourceContext);
        }

        if (realmId == null) {
            throw new RuntimeException("Logic error realmId should not be null");
        }
        return realmId;
    }
}
