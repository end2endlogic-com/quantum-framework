package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;

import java.util.Optional;
import java.util.Set;

/**
 * Context object providing access to authentication/authorization data
 * for use by {@link PrincipalContextPropertiesResolver} implementations.
 *
 * <p>This interface abstracts the various sources of identity information
 * available during the SecurityFilter processing, allowing resolver
 * implementations to access what they need without depending on
 * Quarkus security classes directly.</p>
 *
 * @see PrincipalContextPropertiesResolver
 */
public interface ResolutionContext {

    /**
     * Returns whether the current identity is anonymous (not authenticated).
     *
     * @return true if anonymous, false if authenticated
     */
    boolean isAnonymous();

    /**
     * Returns the principal name from the security identity.
     * This is typically the username or subject identifier.
     *
     * @return the principal name, or null if anonymous
     */
    String getPrincipalName();

    /**
     * Returns the roles from the security identity.
     *
     * @return set of role names, may be empty
     */
    Set<String> getIdentityRoles();

    /**
     * Returns the resolved user ID.
     * This may come from JWT claims, credentials, or the principal name.
     *
     * @return the user ID
     */
    String getUserId();

    /**
     * Returns the subject ID if available.
     * This is typically the 'sub' claim from a JWT token.
     *
     * @return the subject ID, or empty if not available
     */
    Optional<String> getSubjectId();

    /**
     * Returns the resolved realm.
     * This may be from X-Realm header or the default realm.
     *
     * @return the realm name
     */
    String getRealm();

    /**
     * Returns the DataDomain being constructed for this principal.
     *
     * @return the DataDomain
     */
    DataDomain getDataDomain();

    /**
     * Returns the DomainContext if available.
     * This provides complete realm context including tenantId, orgRefName, etc.
     *
     * @return the DomainContext, or empty if not available
     */
    Optional<DomainContext> getDomainContext();

    /**
     * Returns an HTTP request header value.
     *
     * @param name the header name
     * @return the header value, or null if not present
     */
    String getHeader(String name);

    /**
     * Returns a JWT claim value.
     *
     * @param claimName the claim name
     * @return the claim value, or null if not JWT auth or claim not present
     */
    Object getJwtClaim(String claimName);

    /**
     * Returns whether credentials were found in the database for this user.
     *
     * @return true if credentials exist
     */
    boolean hasCredentials();

    /**
     * Returns the user ID from credentials, if available.
     *
     * @return the credential user ID, or empty if no credentials
     */
    Optional<String> getCredentialUserId();

    /**
     * Returns the subject from credentials, if available.
     *
     * @return the credential subject, or empty if no credentials
     */
    Optional<String> getCredentialSubject();

    /**
     * Returns the roles from credentials, if available.
     *
     * @return array of roles from credentials, or empty array if no credentials
     */
    String[] getCredentialRoles();
}
