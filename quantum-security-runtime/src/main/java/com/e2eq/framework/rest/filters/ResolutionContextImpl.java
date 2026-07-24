package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.ResolutionContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;
import java.util.Set;

/**
 * Implementation of ResolutionContext that provides access to authentication
 * data for PrincipalContextPropertiesResolver implementations.
 */
public class ResolutionContextImpl implements ResolutionContext {

    private final SecurityIdentity securityIdentity;
    private final CredentialUserIdPassword credentials;
    private final String realm;
    private final String userId;
    private final JsonWebToken jwt;
    private final ContainerRequestContext requestContext;
    private final DataDomain dataDomain;
    private final DomainContext domainContext;

    public ResolutionContextImpl(
            SecurityIdentity securityIdentity,
            CredentialUserIdPassword credentials,
            String realm,
            String userId,
            JsonWebToken jwt,
            ContainerRequestContext requestContext,
            DataDomain dataDomain,
            DomainContext domainContext) {
        this.securityIdentity = securityIdentity;
        this.credentials = credentials;
        this.realm = realm;
        this.userId = userId;
        this.jwt = jwt;
        this.requestContext = requestContext;
        this.dataDomain = dataDomain;
        this.domainContext = domainContext;
    }

    @Override
    public boolean isAnonymous() {
        return securityIdentity == null || securityIdentity.isAnonymous();
    }

    @Override
    public String getPrincipalName() {
        if (securityIdentity != null && securityIdentity.getPrincipal() != null) {
            return securityIdentity.getPrincipal().getName();
        }
        return null;
    }

    @Override
    public Set<String> getIdentityRoles() {
        if (securityIdentity != null) {
            return securityIdentity.getRoles();
        }
        return Set.of();
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public Optional<String> getSubjectId() {
        // First try JWT sub claim
        if (jwt != null) {
            String sub = jwt.getClaim("sub");
            if (sub != null) {
                return Optional.of(sub);
            }
        }
        // Then try credentials subject
        if (credentials != null && credentials.getSubject() != null) {
            return Optional.of(credentials.getSubject());
        }
        return Optional.empty();
    }

    @Override
    public String getRealm() {
        return realm;
    }

    @Override
    public DataDomain getDataDomain() {
        return dataDomain;
    }

    @Override
    public Optional<DomainContext> getDomainContext() {
        return Optional.ofNullable(domainContext);
    }

    @Override
    public String getHeader(String name) {
        if (requestContext != null) {
            return requestContext.getHeaderString(name);
        }
        return null;
    }

    @Override
    public Object getJwtClaim(String claimName) {
        if (jwt != null) {
            return jwt.getClaim(claimName);
        }
        return null;
    }

    @Override
    public boolean hasCredentials() {
        return credentials != null;
    }

    @Override
    public Optional<String> getCredentialUserId() {
        if (credentials != null) {
            return Optional.ofNullable(credentials.getUserId());
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getCredentialSubject() {
        if (credentials != null) {
            return Optional.ofNullable(credentials.getSubject());
        }
        return Optional.empty();
    }

    @Override
    public String[] getCredentialRoles() {
        if (credentials != null && credentials.getRoles() != null) {
            return credentials.getRoles();
        }
        return new String[0];
    }
}
