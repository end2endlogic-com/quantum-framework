package com.e2eq.framework.rest.usage;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Test-only stand-in for SecurityFilter's authoritative post-authentication context setup. */
@Provider
@Priority(Priorities.USER + 50)
public class UsageGovernanceTestSecurityContextFilter
        implements ContainerRequestFilter, ContainerResponseFilter {

    @Inject
    SecurityIdentity identity;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        SecurityContext.clear();
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        Object tenantAttribute = identity.getAttribute("tenantId");
        String tenant = String.valueOf(tenantAttribute);
        String subject = identity.getPrincipal().getName();
        DataDomain domain = new DataDomain("test-org", "test-account", tenant, 0, subject);
        SecurityContext.setPrincipalContext(new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(domain)
                .withUserId(subject)
                .withSubjectId(subject)
                .withRoles(identity.getRoles().toArray(String[]::new))
                .withScope("test-authenticated")
                .build());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        SecurityContext.clear();
    }
}
