package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.rest.models.AccessibleTenantInfo;
import com.e2eq.framework.model.auth.AccessibleTenantResolver;
import com.e2eq.framework.model.auth.ApplicationAuthorizationResolver;
import com.e2eq.framework.model.auth.ApplicationRealmContextResolver;
import com.e2eq.framework.model.auth.TenantDefaults;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.security.AccountType;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationBindingValidator;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.ServiceKind;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves a verified identity, for one application, to the principal a request runs as:
 * realm, roles, DomainContext, data domain, area overrides, data-domain policy, impersonator
 * and acting service ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 2).
 *
 * <p>This is the credential path that {@code SecurityFilter} has always run for applications
 * holding the credential store, extracted so the filter (embedded mode) and the
 * {@code quantum-auth-service} resolution endpoint (remote mode) share one implementation:
 *
 * <ol>
 *   <li>the credential by subject, then by userId with the subject matching;</li>
 *   <li>the base context in the authority realm (the token's realm claim in
 *       {@link PrincipalContextRequest.Mode#SIGNED_TOKEN_CLAIMS}, the credential's home realm
 *       otherwise), with roles looked up in the requested realm after
 *       {@link #validateRealmAccess};</li>
 *   <li>impersonation, via the credential's {@code impersonateFilterScript};</li>
 *   <li>the realm switch ({@link #applyRealmOverride});</li>
 *   <li>application admission;</li>
 *   <li>tenant selection ({@link #applyTenantSelection}).</li>
 * </ol>
 *
 * <p>In {@link PrincipalContextRequest.Mode#IDENTITY} the identity's application bindings
 * ({@link UserAppRole}, its own and its groups') decide first: a binding for the application in
 * the realm admits it, its tenants (narrowed to the membership's and to installed ones) and default
 * decide the tenant, and its roles for the selected tenant join the realm-wide roles. A group's
 * binding applies only to members with an active membership in the group's owner tenant, and only
 * the identity's own bindings choose a realm. Only when no binding applies does the realm
 * assignment's application grant decide. A binding that breaks the binding rules, or a suspended
 * one, refuses; it never falls back. Bindings are read from the local directory only: where the
 * remote directory holds them ({@link ApplicationAccessDirectory#bindingsReadable()} false) every
 * identity-mode resolution for an application refuses with {@code APPLICATION_NOT_GRANTED}
 * instead of letting the realm assignment decide.
 *
 * <p>{@link PrincipalContextRequest.Mode#SIGNED_TOKEN_CLAIMS} (the embedded filter's legacy
 * path) does not read bindings: the realm assignment's application grant and the token's claims
 * decide, as before bindings existed.
 *
 * <p>{@link PrincipalContextRequest.Mode#IDENTITY} reads nothing from the token beyond the
 * identity, refuses with typed {@link PrincipalResolutionException}s, applies the default-tenant
 * rule for shared realms, and resolves the acted-for identity of a delegating service
 * credential. {@link PrincipalContextRequest.Mode#SIGNED_TOKEN_CLAIMS} is byte-for-byte the
 * filter's behaviour, including the exceptions it throws.
 */
@ApplicationScoped
public class PrincipalContextResolver {

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    IdentityRoleResolver identityRoleResolver;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    ApplicationAccessDirectory accessDirectory;

    ImpersonationScriptEvaluator scriptEvaluator = new ImpersonationScriptEvaluator();

    /**
     * Log marker for an identity-mode admission the realm assignment's application grant decided
     * because no application binding applied: what is left to migrate to bindings.
     */
    public static final String LEGACY_APPLICATION_GRANT = "LEGACY_APPLICATION_GRANT";

    public PrincipalContextResolver() {
    }

    public PrincipalContextResolver(SystemDirectory systemDirectory,
                                    RealmMembershipService realmMembershipService,
                                    CredentialRepo credentialRepo,
                                    IdentityRoleResolver identityRoleResolver,
                                    SecurityUtils securityUtils,
                                    EnvConfigUtils envConfigUtils,
                                    ImpersonationScriptEvaluator scriptEvaluator) {
        this.systemDirectory = systemDirectory;
        this.realmMembershipService = realmMembershipService;
        this.credentialRepo = credentialRepo;
        this.identityRoleResolver = identityRoleResolver;
        this.securityUtils = securityUtils;
        this.envConfigUtils = envConfigUtils;
        this.scriptEvaluator = scriptEvaluator == null ? new ImpersonationScriptEvaluator() : scriptEvaluator;
    }

    public PrincipalContextResolver(SystemDirectory systemDirectory,
                                    RealmMembershipService realmMembershipService,
                                    CredentialRepo credentialRepo,
                                    IdentityRoleResolver identityRoleResolver,
                                    SecurityUtils securityUtils,
                                    EnvConfigUtils envConfigUtils,
                                    ImpersonationScriptEvaluator scriptEvaluator,
                                    ApplicationAccessDirectory accessDirectory) {
        this(systemDirectory, realmMembershipService, credentialRepo, identityRoleResolver, securityUtils,
                envConfigUtils, scriptEvaluator);
        this.accessDirectory = accessDirectory;
    }

    // ------------------------------------------------------------------ resolve

    public PrincipalContextResolution resolve(PrincipalContextRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.identityOnly()) {
            return resolveChecked(request);
        }
        if (request.delegationRequested() && blank(request.applicationId())) {
            // Delegation always names the application it acts in.
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "A delegated resolution must name the application it acts in (applicationId)");
        }
        requireIdentityInputs(request);
        return resolveChecked(withRegisteredRealm(request));
    }

    private PrincipalContextResolution resolveChecked(PrincipalContextRequest request) {
        boolean identity = request.identityOnly();

        CredentialUserIdPassword caller = findCredential(request);
        if (identity && credentialDisabled(caller)) {
            throw refusal(PrincipalResolutionError.PRINCIPAL_DISABLED,
                    "Credential for '" + caller.getUserId() + "' is " + caller.getActiveStatus());
        }

        if (identity && request.delegationRequested()) {
            return resolveDelegated(request, caller);
        }

        String realm = request.realm();
        if (identity && blank(realm) && !request.impersonationRequested()) {
            realm = identityRealm(caller, request.applicationId());
        }
        CredentialUserIdPassword effective = caller;
        PrincipalRef impersonatedBy = null;
        PrincipalContext context;
        if (!request.impersonationRequested()) {
            context = withoutImpersonation(request, buildBaseContext(request, caller, realm));
        } else if (identity) {
            CredentialUserIdPassword target = authorizeIdentityImpersonation(request, caller, realm);
            context = buildImpersonatedContext(target, caller, request.actingOnBehalfOfSubject(),
                    request.actingOnBehalfOfUserId(), request.applicationId());
            effective = target;
            impersonatedBy = new PrincipalRef(caller.getUserId(), caller.getSubject());
        } else {
            // Signed mode keeps the filter's order: the base context (and its realm check) first.
            buildBaseContext(request, caller, realm);
            CredentialUserIdPassword original = impersonatorCredential(request);
            String impersonationRealm = request.signedClaims().impersonationRealm();
            validateImpersonationPermissions(original, impersonationRealm, false);
            CredentialUserIdPassword target = findImpersonationTarget(
                    request.impersonateSubject(), request.impersonateUserId())
                    .orElseThrow(() -> new WebApplicationException(String.format(
                            "Could not find impersonated userId or subject, id:%s", impersonationId(request)),
                            Response.Status.NOT_FOUND));
            context = buildImpersonatedContext(target, original, request.actingOnBehalfOfSubject(),
                    request.actingOnBehalfOfUserId(), request.applicationId());
            effective = target;
            impersonatedBy = new PrincipalRef(original.getUserId(), original.getSubject());
        }

        CredentialUserIdPassword resolved = effective;
        context = applyRealmOverride(context, realm, false, identity, () -> resolved);
        ApplicationGrant grant = null;
        if (identity) {
            grant = bindingGrant(effective, context.getApplicationId(), context.getDefaultRealm());
            if (grant == null) {
                requireApplicationGranted(context, effective);
                // Migration signal: this admission had no binding; the realm assignment decided.
                Log.debugf("%s user=%s application=%s realm=%s: no application binding applies; the realm "
                                + "assignment's application grant decided", LEGACY_APPLICATION_GRANT,
                        effective.getUserId(), context.getApplicationId(), context.getDefaultRealm());
            }
        } else if (request.signedClaims().foreignApplicationToken()) {
            requireSessionApplicationAdmitted(context, () -> resolved);
        }

        if (identity) {
            // A platform operation that needs no tenant keeps the base data domain, exactly as the
            // signed path's tenantSelectionExempt; a requested tenant is still authorized.
            boolean selectTenant = !(request.tenantSelectionOptional() && blank(request.tenantId()));
            if (grant == null) {
                if (selectTenant) {
                    context = applyIdentityTenantSelection(context, request.tenantId(), effective);
                }
            } else if (selectTenant) {
                context = applyBindingTenantSelection(context, request.tenantId(), grant);
                context = withBindingRoles(context, grant);
            }
        } else {
            // The signed tenant claim names a tenant of the realm the token was issued for. It
            // applies only while the request stays in that realm; after an X-Realm switch the
            // caller must select a tenant explicitly.
            String authorityRealm = authorityRealm(request, caller);
            String tokenTenantId = authorityRealm != null && authorityRealm.equals(context.getDefaultRealm())
                    ? request.signedClaims().tenantId()
                    : null;
            context = applyTenantSelection(context, request.tenantId(), tokenTenantId,
                    request.signedClaims().tenantSelectionExempt());
        }
        if (!serviceCredential(effective)) {
            context = identity ? finalizeUserPrincipal(context, grant, effective)
                    : finalizeUserPrincipal(context, effective);
        }
        return new PrincipalContextResolution(context, effective, caller, impersonatedBy, null);
    }

    /**
     * Identity-mode impersonation, caller first as {@code SecurityFilter} does: a service
     * credential never impersonates (acting for an identity is delegation, DELEGATING only); the
     * caller needs a filter script, and a requested realm is checked for realm access and by the
     * script before any target is looked up. The impersonation then runs in the target's home
     * realm, which must be the requested one, and is checked again there. Every failure after
     * the caller checks is the same IMPERSONATION_REFUSED, so a refusal does not reveal whether
     * the target exists, is disabled, or lives elsewhere.
     */
    private CredentialUserIdPassword authorizeIdentityImpersonation(PrincipalContextRequest request,
                                                                   CredentialUserIdPassword caller,
                                                                   String realm) {
        if (serviceCredential(caller)) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "Service credential '" + caller.getUserId() + "' may not impersonate; delegation is for "
                            + "DELEGATING service credentials");
        }
        if (caller.getImpersonateFilterScript() == null) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "'" + caller.getUserId() + "' has no impersonation filter script");
        }
        if (realm != null) {
            validateRealmAccess(caller, realm, true);
            validateImpersonationPermissions(caller, realm, true);
        }
        String id = impersonationId(request);
        CredentialUserIdPassword target = findImpersonationTarget(
                request.impersonateSubject(), request.impersonateUserId())
                .orElseThrow(() -> refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                        "No credential for impersonation target " + id));
        if (credentialDisabled(target)) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "Impersonation target " + id + " is " + target.getActiveStatus());
        }
        String targetRealm = target.getDomainContext().getDefaultRealm();
        if (realm != null && !realm.equals(targetRealm)) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "Impersonation target " + id + " runs in realm " + targetRealm + ", not " + realm);
        }
        try {
            validateRealmAccess(caller, targetRealm, true);
            validateImpersonationPermissions(caller, targetRealm, true);
        } catch (PrincipalResolutionException refused) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED, refused.getMessage());
        }
        requireListedImpersonationTarget(caller, target);
        return target;
    }

    /**
     * The requested realm as its registered refName, compared ignoring case ({@link RealmRefNames}):
     * a realm named in another case is the same realm, never a second database name for a lookup
     * (MongoDB refuses one that differs only in case). A realm that matches none is passed on
     * unchanged and refused typed by the realm checks that follow ({@code REALM_NOT_AUTHORIZED},
     * or {@code DELEGATION_NOT_ALLOWED} for a delegation).
     */
    private PrincipalContextRequest withRegisteredRealm(PrincipalContextRequest request) {
        String requested = request.realm();
        String canonical = RealmRefNames.canonicalOrSame(systemDirectory, requested);
        if (canonical == null || canonical.equals(requested)) {
            return request;
        }
        return new PrincipalContextRequest(request.mode(), request.subject(), request.userIdCandidates(),
                request.applicationId(), canonical, request.tenantId(), request.impersonateSubject(),
                request.impersonateUserId(), request.actingOnBehalfOfSubject(), request.actingOnBehalfOfUserId(),
                request.signedClaims(), request.tenantSelectionOptional());
    }

    private void requireIdentityInputs(PrincipalContextRequest request) {
        // The verified subject is the identity; a userId claim alone is not proof of a credential.
        if (!PrincipalContextRequest.nonBlank(request.subject())) {
            throw refusal(PrincipalResolutionError.PRINCIPAL_UNKNOWN, "The token names no subject");
        }
        if (!PrincipalContextRequest.nonBlank(request.applicationId())) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED,
                    "applicationId is required to resolve a principal");
        }
        if (request.impersonateSubject() != null && request.impersonateUserId() != null) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "Impersonation names a subject and a userId; pass only one");
        }
        if (request.delegationRequested() && request.impersonationRequested()) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "A request acts on behalf of an identity or impersonates one, not both");
        }
    }

    private static String impersonationId(PrincipalContextRequest request) {
        return request.impersonateSubject() != null ? request.impersonateSubject() : request.impersonateUserId();
    }

    // ------------------------------------------------------------------ credential

    private CredentialUserIdPassword findCredential(PrincipalContextRequest request) {
        String systemRealm = envConfigUtils.getSystemRealm();
        String sub = request.subject();
        if (sub == null && !request.identityOnly()) {
            throw new IllegalStateException("sub attribute not provided but is required in token claims");
        }
        Optional<CredentialUserIdPassword> found = sub == null
                ? Optional.empty()
                : credentialForSubject(sub);
        if (found.isEmpty()) {
            for (String candidate : request.userIdCandidates()) {
                Optional<CredentialUserIdPassword> byUserId = credentialRepo.findByUserId(candidate, systemRealm, true);
                if (byUserId.isEmpty()) {
                    continue;
                }
                CredentialUserIdPassword creds = byUserId.get();
                if (sub == null || Objects.equals(creds.getSubject(), sub)) {
                    found = byUserId;
                    break;
                }
                String text = String.format(
                        "Found user with userId %s but subject is:%s but token has subject:%s in the database, roles in credential is %s",
                        candidate, creds.getSubject(), sub, Arrays.toString(creds.getRoles()));
                Log.warn(text);
                if (request.identityOnly()) {
                    throw refusal(PrincipalResolutionError.PRINCIPAL_UNKNOWN,
                            "The token's userId belongs to another subject");
                }
                throw new IllegalStateException(text);
            }
        }
        if (found.isEmpty()) {
            if (request.identityOnly()) {
                throw refusal(PrincipalResolutionError.PRINCIPAL_UNKNOWN,
                        "No credential for the verified identity");
            }
            throw new IllegalStateException(String.format(
                    "Subject %s not found in credentialUserIdPassword collection in realm:%s", sub, systemRealm));
        }
        return found.get();
    }

    /**
     * The authentication lookup: the credential a verified token subject names, in the identity
     * store (the system realm). It runs before any principal exists, because its answer is what the
     * principal is built from, so there is no caller whose repository rules could govern it; every
     * bearer resolved here is read this way, once per resolution. Authorization (realm access,
     * application admission, tenant selection, roles) is decided afterwards from this credential and
     * its grants. Callers that must check the credential before resolving (for example
     * {@code SecurityFilter}'s service-token admission) use this method rather than a lookup of
     * their own.
     */
    public Optional<CredentialUserIdPassword> credentialForSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return credentialRepo.findBySubject(subject, envConfigUtils.getSystemRealm(), true);
    }

    static boolean credentialDisabled(CredentialUserIdPassword credential) {
        com.e2eq.framework.model.persistent.base.ActiveStatus status = credential.getActiveStatus();
        return status == com.e2eq.framework.model.persistent.base.ActiveStatus.INACTIVE
                || status == com.e2eq.framework.model.persistent.base.ActiveStatus.DELETED;
    }

    static boolean serviceCredential(CredentialUserIdPassword credential) {
        return credential.getCredentialType() == CredentialType.SERVICE_TOKEN
                || credential.getAccountType() == AccountType.SERVICE;
    }

    // ------------------------------------------------------------------ base context

    private String authorityRealm(PrincipalContextRequest request, CredentialUserIdPassword creds) {
        String credentialRealm = creds.getDomainContext().getDefaultRealm();
        if (request.identityOnly()) {
            return credentialRealm;
        }
        String signedRealm = request.signedClaims().realm();
        return signedRealm == null ? credentialRealm : signedRealm;
    }

    private PrincipalContext buildBaseContext(PrincipalContextRequest request,
                                              CredentialUserIdPassword creds,
                                              String realmOverride) {
        // Keep the base context on the authority realm. The requested realm is applied later by
        // applyRealmOverride(), but the target realm is needed here for role resolution.
        String authorityRealm = authorityRealm(request, creds);
        String roleLookupRealm = authorityRealm;
        if (realmOverride != null) {
            validateRealmAccess(creds, realmOverride, request.identityOnly());
            roleLookupRealm = realmOverride;
        }
        SecurityIdentity rolesIdentity = request.identityOnly() ? null : request.signedClaims().rolesIdentity();
        String[] roles = resolveEffectiveRoles(rolesIdentity, creds, authorityRealm, roleLookupRealm,
                request.applicationId());
        DomainContext effectiveDomainContext = resolveDomainContext(creds, authorityRealm, request.identityOnly());
        DataDomain dataDomain = effectiveDomainContext.toDataDomain(creds.getUserId());
        return new PrincipalContext.Builder()
                .withDefaultRealm(authorityRealm)
                .withApplicationId(request.applicationId())
                .withDomainContext(effectiveDomainContext)
                .withDataDomain(dataDomain)
                .withUserId(creds.getUserId())
                .withRoles(roles)
                .withScope(scopeOf(request, creds))
                .withArea2RealmOverrides(creds.getArea2RealmOverrides())
                .withDataDomainPolicy(creds.getDataDomainPolicy())
                .build();
    }

    private static String scopeOf(PrincipalContextRequest request, CredentialUserIdPassword creds) {
        return request.identityOnly() && serviceCredential(creds) ? PrincipalContext.SCOPE_SERVICE : "AUTHENTICATED";
    }

    /** Effective roles in {@code effectiveRealm} through {@link IdentityRoleResolver}. */
    public String[] resolveEffectiveRoles(SecurityIdentity identity,
                                          CredentialUserIdPassword credential,
                                          String authorityRealm,
                                          String effectiveRealm,
                                          String applicationId) {
        if (identityRoleResolver == null) {
            throw new IllegalStateException("IdentityRoleResolver is required to build an authenticated principal");
        }
        return identityRoleResolver.resolveEffectiveRoles(
                identity, credential, authorityRealm, effectiveRealm, applicationId);
    }

    /**
     * Refuses a realm that is unregistered or outside the credential's realm authorization
     * ({@code authorizedRealms}, then {@code realmRegEx}). A null realm is not checked.
     */
    public void validateRealmAccess(CredentialUserIdPassword creds, String realm) {
        validateRealmAccess(creds, realm, false);
    }

    void validateRealmAccess(CredentialUserIdPassword creds, String realm, boolean typed) {
        if (realm == null) {
            return;
        }
        if (systemDirectory.findRealmByRefName(realm).isEmpty()) {
            throw fail(typed, PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    String.format("The realm override %s is not registered", realm),
                    jakarta.ws.rs.ForbiddenException::new);
        }
        List<String> realmsAuthorized = securityUtils.computeAllowedRealmRefNames(creds, List.of(realm));
        if (!realmsAuthorized.contains(realm)) {
            throw fail(typed, PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    String.format("The user %s is not authorized to access realm %s", creds.getUserId(), realm),
                    jakarta.ws.rs.ForbiddenException::new);
        }
    }

    /** The credential's home DomainContext in its home realm, otherwise the realm catalog's. */
    public DomainContext resolveDomainContext(CredentialUserIdPassword credential, String realm) {
        return resolveDomainContext(credential, realm, false);
    }

    DomainContext resolveDomainContext(CredentialUserIdPassword credential, String realm, boolean typed) {
        DomainContext home = credential.getDomainContext();
        if (home != null && realm != null && realm.equalsIgnoreCase(home.getDefaultRealm())) {
            return home;
        }
        Realm target = systemDirectory.findRealmByRefName(realm)
                .orElseThrow(() -> fail(typed, PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                        "Realm '" + realm + "' is not registered", jakarta.ws.rs.ForbiddenException::new));
        if (target.getDomainContext() == null) {
            throw fail(typed, PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    "Realm '" + realm + "' has no data-domain configuration", jakarta.ws.rs.ForbiddenException::new);
        }
        return target.getDomainContext();
    }

    private PrincipalContext withoutImpersonation(PrincipalContextRequest request, PrincipalContext base) {
        PrincipalContextRequest.SignedClaims claims = request.signedClaims();
        return new PrincipalContext.Builder()
                .withDefaultRealm(base.getDefaultRealm())
                .withApplicationId(base.getApplicationId())
                .withDomainContext(base.getDomainContext())
                .withDataDomain(base.getDataDomain())
                .withUserId(base.getUserId())
                .withRoles(base.getRoles())
                .withScope(base.getScope())
                .withArea2RealmOverrides(base.getArea2RealmOverrides())
                .withDataDomainPolicy(base.getDataDomainPolicy())
                .withRealmOverrideActive(base.isRealmOverrideActive())
                .withOriginalDataDomain(base.getOriginalDataDomain())
                .withActingOnBehalfOfSubject(claims == null ? null : request.actingOnBehalfOfSubject())
                .withActingOnBehalfOfUserId(claims == null ? null : request.actingOnBehalfOfUserId())
                .withImpersonatedBySubject(claims == null ? null : claims.originalSubject())
                .withImpersonatedByUserId(claims == null ? null : claims.originalUserId())
                .build();
    }

    // ------------------------------------------------------------------ impersonation

    private CredentialUserIdPassword impersonatorCredential(PrincipalContextRequest request) {
        String sub = request.subject();
        if (sub == null) {
            if (request.identityOnly()) {
                throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                        "Cannot impersonate without a verified subject");
            }
            throw new IllegalStateException("Cannot impersonate without valid JWT subject");
        }
        Optional<CredentialUserIdPassword> original =
                credentialRepo.findBySubject(sub, envConfigUtils.getSystemRealm(), true);
        if (original.isEmpty()) {
            if (request.identityOnly()) {
                throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                        "Original user credential not found for impersonation");
            }
            throw new IllegalStateException("Original user credential not found for impersonation");
        }
        return original.get();
    }

    /**
     * Refuses impersonation unless the credential's {@code impersonateFilterScript} permits it in
     * {@code realm} (the credential store's database when null).
     */
    public void validateImpersonationPermissions(CredentialUserIdPassword originalCreds, String realm) {
        validateImpersonationPermissions(originalCreds, realm, false);
    }

    void validateImpersonationPermissions(CredentialUserIdPassword originalCreds, String realm, boolean typed) {
        if (originalCreds.getImpersonateFilterScript() == null) {
            throw fail(typed, PrincipalResolutionError.IMPERSONATION_REFUSED,
                    String.format("subject %s with userId %s is not configured with a impersonateFilter in realm:%s",
                            originalCreds.getSubject(), originalCreds.getUserId(), credentialRepo.getDatabaseName()),
                    IllegalArgumentException::new);
        }
        String targetRealm = (realm != null) ? realm : credentialRepo.getDatabaseName();
        boolean permitted;
        try {
            permitted = scriptEvaluator.evaluate(originalCreds.getSubject(), originalCreds.getUserId(), targetRealm,
                    originalCreds.getImpersonateFilterScript());
        } catch (ImpersonationScriptEvaluator.ScriptEvaluationException unevaluated) {
            throw fail(typed, PrincipalResolutionError.IMPERSONATION_REFUSED, unevaluated.getMessage(),
                    message -> new WebApplicationException(message, Response.Status.FORBIDDEN));
        }
        if (!permitted) {
            throw fail(typed, PrincipalResolutionError.IMPERSONATION_REFUSED,
                    String.format("User %s with userId %s is not authorized to impersonate in realm:%s",
                            originalCreds.getSubject(), originalCreds.getUserId(), targetRealm),
                    message -> new WebApplicationException(message, Response.Status.FORBIDDEN));
        }
    }

    /** The impersonation (or delegation) target by subject, else by userId. */
    public Optional<CredentialUserIdPassword> findImpersonationTarget(String impersonateSubject, String impersonateUserId) {
        if (impersonateSubject != null) {
            return credentialRepo.findBySubject(impersonateSubject, envConfigUtils.getSystemRealm(), true);
        } else if (impersonateUserId != null) {
            return credentialRepo.findByUserId(impersonateUserId, envConfigUtils.getSystemRealm(), true);
        }
        throw new IllegalStateException("Neither impersonateSubject nor impersonateUserId provided");
    }

    /** When the credential lists {@code authorizedImpersonateIds}, the target must be one of them. */
    private void requireListedImpersonationTarget(CredentialUserIdPassword original, CredentialUserIdPassword target) {
        if (!listedTarget(original, target)) {
            throw refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                    "'" + target.getUserId() + "' is not among the identities '" + original.getUserId()
                            + "' may impersonate");
        }
    }

    private static boolean listedTarget(CredentialUserIdPassword original, CredentialUserIdPassword target) {
        List<CredentialUserIdPassword.ImpersonateEntry> listed = original.getAuthorizedImpersonateIds();
        if (listed == null || listed.isEmpty()) {
            return true;
        }
        return listed.stream().filter(Objects::nonNull).anyMatch(entry ->
                (entry.getUserId() != null && entry.getUserId().equalsIgnoreCase(target.getUserId()))
                        || (entry.getSubject() != null && entry.getSubject().equals(target.getSubject())));
    }

    /**
     * The principal of an impersonation: the target credential in its own home realm, with roles
     * resolved without the impersonator's token roles.
     */
    public PrincipalContext buildImpersonatedContext(CredentialUserIdPassword targetCreds,
                                                     CredentialUserIdPassword originalCreds,
                                                     String actingOnBehalfOfSubject,
                                                     String actingOnBehalfOfUserId,
                                                     String applicationId) {
        String targetRealm = targetCreds.getDomainContext().getDefaultRealm();
        String[] roles = resolveEffectiveRoles(null, targetCreds, targetRealm, targetRealm, applicationId);
        DataDomain dataDomain = targetCreds.getDomainContext().toDataDomain(targetCreds.getUserId());
        return new PrincipalContext.Builder()
                .withDefaultRealm(targetRealm)
                .withApplicationId(applicationId)
                .withDomainContext(targetCreds.getDomainContext())
                .withDataDomain(dataDomain)
                .withUserId(targetCreds.getUserId())
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .withArea2RealmOverrides(targetCreds.getArea2RealmOverrides())
                .withImpersonatedBySubject(originalCreds.getSubject())
                .withImpersonatedByUserId(originalCreds.getUserId())
                .withActingOnBehalfOfUserId(actingOnBehalfOfUserId)
                .withActingOnBehalfOfSubject(actingOnBehalfOfSubject)
                .withDataDomainPolicy(targetCreds.getDataDomainPolicy())
                .build();
    }

    // ------------------------------------------------------------------ delegation

    /**
     * A delegating service credential acting on behalf of an identity: the scope check reuses the
     * service credential's impersonation authorization ({@code authorizedImpersonateIds},
     * {@code impersonateFilterScript}, {@code authorizedRealms}/{@code realmRegEx}); the result is
     * the acted-for identity resolved as if it had signed in, for the same application, recording
     * the acting service.
     */
    private PrincipalContextResolution resolveDelegated(PrincipalContextRequest request,
                                                        CredentialUserIdPassword service) {
        if (!serviceCredential(service) || service.getServiceKind() != ServiceKind.DELEGATING) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Only a delegating service credential may act on behalf of an identity");
        }
        boolean listed = service.getAuthorizedImpersonateIds() != null
                && !service.getAuthorizedImpersonateIds().isEmpty();
        boolean scripted = service.getImpersonateFilterScript() != null;
        if (!listed && !scripted) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Service credential '" + service.getUserId() + "' has no delegation scope");
        }
        // The service's own permission is settled before any acted-for identity is looked up, so
        // a requested realm outside its reach refuses the same way whoever is named.
        requireServiceGranted(service, request.applicationId());
        if (request.realm() != null) {
            authorizeDelegationRealm(service, request.realm(), scripted);
        }

        // From here on every refusal is the same: an unknown, disabled or out-of-scope identity
        // must not be told apart, or delegation becomes a directory probe.
        String targetSubject = PrincipalContextRequest.nonBlank(request.actingOnBehalfOfSubject())
                ? request.actingOnBehalfOfSubject().trim() : null;
        String targetUserId = targetSubject == null ? request.actingOnBehalfOfUserId().trim() : null;
        CredentialUserIdPassword target = findImpersonationTarget(targetSubject, targetUserId)
                .orElseThrow(() -> refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                        "No credential for the acted-for identity"));
        if (credentialDisabled(target)) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "The acted-for identity is " + target.getActiveStatus());
        }
        if (listed && !listedTarget(service, target)) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "'" + target.getUserId() + "' is outside the delegation scope of '" + service.getUserId() + "'");
        }
        // With no requested realm the acted-for identity runs where it would on its own (the
        // realm its application owns, else its home realm). That realm is decided once here, the
        // service's scope must admit it, and it is passed on explicitly. A realm refusal reads as
        // DELEGATION_NOT_ALLOWED, like every other refusal about the acted-for identity.
        String targetRealm = request.realm();
        if (targetRealm == null) {
            try {
                String applicationRealm = identityRealm(target, request.applicationId());
                targetRealm = applicationRealm != null ? applicationRealm : target.getDomainContext().getDefaultRealm();
            } catch (PrincipalResolutionException refused) {
                throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, refused.getMessage());
            }
            authorizeDelegationRealm(service, targetRealm, scripted);
        }

        PrincipalContextRequest asTarget = PrincipalContextRequest.identity(target.getSubject(), target.getUserId())
                .applicationId(request.applicationId())
                .realm(targetRealm)
                .tenantId(request.tenantId())
                .tenantSelectionOptional(request.tenantSelectionOptional())
                .build();
        PrincipalContextResolution resolved = resolve(asTarget);
        return new PrincipalContextResolution(resolved.context(), resolved.credential(), service, null,
                new PrincipalRef(service.getUserId(), service.getSubject()));
    }

    /**
     * A delegating service acts on behalf of identities only for an application it is itself
     * explicitly granted: the delegated principal carries the acted-for identity's roles only, so
     * without this a receiver could not tell an admitted calling service from any other delegating
     * credential. Delegation always names the application; a delegated resolution that names none is
     * refused {@code DELEGATION_NOT_ALLOWED}. The service's realm for the application is its binding
     * marked {@code defaultRealm}, else the realm the application owns, else its home realm. There
     * the grant must be explicit and active: its active binding, or its active realm assignment's
     * application grant, or its credential's application pattern. Ownership of the realm by the
     * application is not a grant for a delegating service. Refused {@code APPLICATION_NOT_GRANTED}
     * naming the service, before any acted-for identity is read.
     */
    private void requireServiceGranted(CredentialUserIdPassword service, String applicationId) {
        if (blank(applicationId)) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, String.format(
                    "Delegating service '%s' must name the application it acts in", service.getUserId()));
        }
        String app = applicationId.trim();
        String realm;
        ApplicationGrant grant;
        try {
            String serviceRealm = identityRealm(service, app);
            realm = serviceRealm != null ? serviceRealm
                    : service.getDomainContext() == null ? null : service.getDomainContext().getDefaultRealm();
            grant = blank(realm) ? null : bindingGrant(service, app, realm);
        } catch (PrincipalResolutionException refused) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "Delegating service '%s' is not granted application '%s': %s",
                    service.getUserId(), app, refused.getMessage()));
        }
        if (grant == null && !explicitlyGranted(service, realm, app)) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "Delegating service '%s' is not granted application '%s' in realm '%s'",
                    service.getUserId(), app, realm));
        }
    }

    /**
     * An explicit, active grant of {@code applicationId} to {@code identity} in {@code realm}: its
     * active realm assignment names the application, or its credential's application pattern admits
     * it. Unlike {@link #applicationAdmitted}, the realm's own application is not a grant, and an
     * assignment that is not active (invited, suspended) grants nothing, pattern included.
     */
    private boolean explicitlyGranted(CredentialUserIdPassword identity, String realm, String applicationId) {
        if (blank(realm) || realmMembershipService == null) {
            return false;
        }
        Optional<UserRealmRole> assignment = realmMembershipService.assignmentForUser(identity.getUserId(), realm);
        if (assignment.isPresent() && assignment.get().getStatus() != null
                && !UserRealmRole.STATUS_ACTIVE.equalsIgnoreCase(assignment.get().getStatus().trim())) {
            return false;
        }
        List<String> assigned = assignment.map(UserRealmRole::getAuthorizedApplications).orElse(null);
        if (ApplicationAuthorizationResolver.resolve(assigned, null, null, applicationId).resolved()) {
            return true;
        }
        String pattern = identity.getApplicationRegEx();
        return !blank(pattern)
                && ApplicationAuthorizationResolver.resolve(assigned, pattern, null, applicationId).resolved();
    }

    /**
     * The service's own realm authorization bounds delegation, with the usual reading (no
     * authorizedRealms and no realmRegEx means its home realm only), and its script, if any, must
     * admit the realm.
     */
    private void authorizeDelegationRealm(CredentialUserIdPassword service, String realm, boolean scripted) {
        try {
            validateRealmAccess(service, realm, true);
        } catch (PrincipalResolutionException outside) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Realm " + realm + " is outside the delegation scope of '" + service.getUserId() + "': "
                            + outside.getMessage());
        }
        boolean permitted = true;
        if (scripted) {
            try {
                permitted = scriptEvaluator.evaluate(service.getSubject(), service.getUserId(), realm,
                        service.getImpersonateFilterScript());
            } catch (ImpersonationScriptEvaluator.ScriptEvaluationException unevaluated) {
                // Logged at ERROR by the evaluator: a deployment fault, not the credential's policy.
                throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, unevaluated.getMessage());
            }
        }
        if (!permitted) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Service credential '" + service.getUserId() + "' may not delegate in realm " + realm);
        }
    }

    // ------------------------------------------------------------------ application bindings

    /**
     * The realm an identity-only request runs in when it names none. Among the realms its
     * credential may use, the identity's own active binding marked {@code defaultRealm} decides;
     * several marked refuse with {@code REALM_NOT_AUTHORIZED}. A binding without the mark never
     * chooses a realm, nor does a group binding: {@link #applicationRealm} decides.
     */
    String identityRealm(CredentialUserIdPassword credential, String applicationId) {
        String bound = bindingRealm(credential, applicationId);
        return bound != null ? bound : applicationRealm(credential, applicationId);
    }

    String bindingRealm(CredentialUserIdPassword credential, String applicationId) {
        if (accessDirectory == null || blank(applicationId)) {
            return null;
        }
        requireBindingsReadable(applicationId);
        List<UserAppRole> active = accessDirectory.bindingsFor(credential, applicationId.trim(), null).stream()
                .filter(Objects::nonNull)
                .filter(binding -> !binding.groupBinding())
                .filter(UserAppRole::active)
                .filter(binding -> !blank(binding.getRealmRefName()))
                .toList();
        if (active.isEmpty()) {
            return null;
        }
        List<String> allowed = securityUtils.computeAllowedRealmRefNames(credential,
                active.stream().map(binding -> binding.getRealmRefName().trim()).distinct().toList());
        if (allowed.isEmpty()) {
            return null;
        }
        List<String> defaults = active.stream()
                .filter(binding -> Boolean.TRUE.equals(binding.getDefaultRealm()))
                .map(binding -> binding.getRealmRefName().trim())
                .filter(allowed::contains)
                .distinct()
                .toList();
        if (defaults.size() == 1) {
            return defaults.get(0);
        }
        if (defaults.size() > 1) {
            throw refusal(PrincipalResolutionError.REALM_NOT_AUTHORIZED, String.format(
                    "%s has several default realms %s for application '%s'; the realm must be selected explicitly",
                    credential.getUserId(), defaults, applicationId));
        }
        // Without an explicit default the application's realm decides, even for a single binding:
        // only the subject or the platform marks a binding's realm as the default.
        return null;
    }

    /**
     * What the identity's bindings grant for the application in the realm, or {@code null} when
     * no binding applies (the realm assignment's grant then decides). The identity's own binding
     * always applies. A group binding applies only to members with an active membership in the
     * group's owner tenant (in a binding of a group without one, in one of the binding's tenants);
     * for anyone else it is ignored. Every applying binding is checked again against the binding
     * rules and the registered application, and a group binding must grant exactly its group's
     * owner tenant: one that fails, a duplicate, a suspended user binding, or applying bindings
     * that are all suspended refuse with {@code APPLICATION_NOT_GRANTED}; none of these falls back. Tenants whose install is gone do not count, and bindings
     * left with none refuse.
     */
    ApplicationGrant bindingGrant(CredentialUserIdPassword credential, String applicationId, String realmRefName) {
        if (accessDirectory == null || blank(applicationId) || blank(realmRefName)) {
            return null;
        }
        requireBindingsReadable(applicationId);
        String app = applicationId.trim();
        List<UserAppRole> rows = accessDirectory.bindingsFor(credential, app, realmRefName).stream()
                .filter(Objects::nonNull)
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        Application application = accessDirectory.application(app).orElse(null);
        List<UserAppRole> own = rows.stream().filter(binding -> !binding.groupBinding()).toList();
        if (own.size() > 1) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "%s has %d bindings for application '%s' in realm '%s'; one is allowed",
                    credential.getUserId(), own.size(), app, realmRefName));
        }
        List<UserAppRole> applying = new java.util.ArrayList<>();
        for (UserAppRole binding : own) {
            requireWellFormed(binding, credential, application, app, realmRefName);
            applying.add(binding);
        }
        for (UserAppRole binding : rows) {
            if (!binding.groupBinding()) {
                continue;
            }
            String owner = accessDirectory.groupOwnerTenant(binding.groupRefName());
            List<String> reach = owner != null ? List.of(owner) : ApplicationBindingValidator.tenants(binding);
            if (reach.stream().noneMatch(tenant -> activeMember(credential.getUserId(), realmRefName, tenant))) {
                continue;
            }
            requireWellFormed(binding, credential, application, app, realmRefName);
            if (owner != null && !ApplicationBindingValidator.tenants(binding).equals(List.of(owner))) {
                throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                        "binding %s of group '%s' for application '%s' in realm '%s' is malformed: it must grant "
                                + "exactly the group's owner tenant '%s'",
                        binding.getId(), binding.groupRefName(), app, realmRefName, owner));
            }
            applying.add(binding);
        }
        if (applying.isEmpty()) {
            return null;
        }
        if (!own.isEmpty() && !own.get(0).active()) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "%s's binding for application '%s' in realm '%s' is %s",
                    credential.getUserId(), app, realmRefName, own.get(0).getStatus()));
        }
        List<UserAppRole> active = applying.stream().filter(UserAppRole::active).toList();
        if (active.isEmpty()) {
            // Every binding that applies is suspended: that is a decision, not an absence.
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "every binding of %s for application '%s' in realm '%s' is suspended",
                    credential.getUserId(), app, realmRefName));
        }
        List<BoundTenants> bound = active.stream()
                .map(binding -> new BoundTenants(binding, ApplicationBindingValidator.tenants(binding).stream()
                        .filter(tenant -> accessDirectory.installed(app, realmRefName, tenant))
                        .toList()))
                .toList();
        ApplicationGrant grant = new ApplicationGrant(app, realmRefName, bound,
                own.isEmpty() ? null : own.get(0).getDefaultTenantId());
        if (grant.tenants().isEmpty()) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "application '%s' is not installed for any tenant %s binds in realm '%s'",
                    app, credential.getUserId(), realmRefName));
        }
        return grant;
    }

    /**
     * Bindings decide before the realm assignment, so when they cannot be read here (the remote
     * directory holds them) the assignment must not decide in their place: refuse.
     */
    private void requireBindingsReadable(String applicationId) {
        if (!accessDirectory.bindingsReadable()) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "application bindings for '%s' are held by the remote directory and cannot be read here; "
                            + "resolve principals where the directory is owned", applicationId));
        }
    }

    /**
     * Whether {@code userId} has an active membership in {@code tenantId} of the realm: an active
     * realm assignment that authorizes the tenant or, in a dedicated realm, whose one tenant it is.
     */
    private boolean activeMember(String userId, String realmRefName, String tenantId) {
        if (realmMembershipService == null || blank(tenantId)) {
            return false;
        }
        Optional<UserRealmRole> assignment = realmMembershipService.assignmentForUser(userId, realmRefName)
                .filter(PrincipalContextResolver::activeAssignment);
        if (assignment.isEmpty() || assignment.get().externalInTenant(tenantId)) {
            return false;
        }
        if (AccessibleTenantResolver.isAuthorized(assignment.get(), tenantId)) {
            return true;
        }
        return systemDirectory.findRealmByRefName(realmRefName)
                .filter(realm -> realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT)
                .map(Realm::getDomainContext)
                .map(DomainContext::getTenantId)
                .filter(tenantId.trim()::equals)
                .isPresent();
    }

    private void requireWellFormed(UserAppRole binding, CredentialUserIdPassword credential,
                                   Application application, String applicationId, String realmRefName) {
        boolean subjectMatches = binding.groupBinding()
                || (binding.getUserId() != null && binding.getUserId().trim().equals(credential.getUserId()));
        boolean scopeMatches = binding.getApplicationId() != null
                && applicationId.equals(binding.getApplicationId().trim())
                && binding.getRealmRefName() != null
                && realmRefName.equals(binding.getRealmRefName().trim());
        if (!subjectMatches || !scopeMatches) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "binding %s does not belong to %s for application '%s' in realm '%s'",
                    binding.getId(), credential.getUserId(), applicationId, realmRefName));
        }
        try {
            // Installs are checked per tenant afterwards, so an uninstalled tenant narrows the grant.
            // Reserved roles (the platform's, and the configured membership-administrator roles)
            // would join the global roles, so a row granting one is refused, not filtered.
            ApplicationBindingValidator.validate(binding, application, tenant -> true,
                    accessDirectory.alsoReservedRoles());
        } catch (ApplicationAccessException malformed) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "binding %s for application '%s' in realm '%s' is malformed: %s",
                    binding.getId(), applicationId, realmRefName, malformed.getMessage()));
        }
    }

    /** One binding and the tenants of it the application is installed for. */
    record BoundTenants(UserAppRole binding, List<String> tenants) {
    }

    /** What an identity's bindings grant for one application in one realm. */
    record ApplicationGrant(String applicationId, String realmRefName, List<BoundTenants> bindings,
                            String defaultTenantId) {

        /** Every installed tenant some binding grants. */
        Set<String> tenants() {
            Set<String> out = new LinkedHashSet<>();
            bindings.forEach(bound -> out.addAll(bound.tenants()));
            return out;
        }

        /** Roles of the bindings that grant {@code tenantId}. */
        Set<String> rolesFor(String tenantId) {
            Set<String> out = new LinkedHashSet<>();
            if (tenantId == null) {
                return out;
            }
            for (BoundTenants bound : bindings) {
                if (bound.tenants().contains(tenantId) && bound.binding().getRoles() != null) {
                    bound.binding().getRoles().stream().filter(Objects::nonNull).map(String::trim).forEach(out::add);
                }
            }
            return out;
        }
    }

    /**
     * Tenant selection under a binding. The identity needs an active realm assignment in either
     * tenancy. A dedicated realm is its one tenant, which the binding must grant. In a shared realm the identity needs an active membership; the tenants it may
     * use are those the binding grants (installed) that the membership also authorizes. The tenant
     * is the requested one, else the binding's default, else the only one; several and no default
     * fail {@code TENANT_SELECTION_REQUIRED}.
     */
    PrincipalContext applyBindingTenantSelection(PrincipalContext context, String requestedTenantId,
                                                 ApplicationGrant grant) {
        String effectiveRealm = context.getDefaultRealm();
        String headerTenantId = blank(requestedTenantId) ? null : requestedTenantId.trim();
        Realm realm = systemDirectory.findRealmByRefName(effectiveRealm)
                .orElseThrow(() -> refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Realm '" + effectiveRealm + "' is not registered"));
        Set<String> bound = grant.tenants();
        if (realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT) {
            // A binding admits only a current member: a suspended or removed realm assignment
            // ends access even though the binding remains.
            boolean member = realmMembershipService != null && realmMembershipService
                    .assignmentForUser(context.getUserId(), effectiveRealm)
                    .filter(PrincipalContextResolver::activeAssignment)
                    .isPresent();
            if (!member) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Principal has no active membership in realm '" + effectiveRealm + "'");
            }
            DomainContext realmContext = realm.getDomainContext();
            String realmTenant = realmContext == null ? null : realmContext.getTenantId();
            if (realmTenant == null || !bound.contains(realmTenant)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, String.format(
                        "The binding for application '%s' does not grant tenant '%s' of realm '%s'",
                        grant.applicationId(), realmTenant, effectiveRealm));
            }
            if (headerTenantId != null && !headerTenantId.equals(realmTenant)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Tenant '" + headerTenantId + "' is not the single tenant of realm '" + effectiveRealm + "'");
            }
            if (sameDataCoordinate(realmContext, context.getDomainContext())) {
                return context;
            }
            return copy(context)
                    .withDomainContext(realmContext)
                    .withDataDomain(realmContext.toDataDomain(context.getUserId()))
                    .build();
        }

        UserRealmRole assignment = (realmMembershipService == null ? Optional.<UserRealmRole>empty()
                : realmMembershipService.assignmentForUser(context.getUserId(), effectiveRealm))
                .filter(PrincipalContextResolver::activeAssignment)
                .orElseThrow(() -> refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Principal has no active membership in realm '" + effectiveRealm + "'"));
        List<String> usable = bound.stream()
                .filter(tenant -> AccessibleTenantResolver.isAuthorized(assignment, tenant))
                .toList();
        String tenantId;
        if (headerTenantId != null) {
            if (!usable.contains(headerTenantId)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, String.format(
                        "Tenant '%s' is not granted for application '%s' in realm '%s'",
                        headerTenantId, grant.applicationId(), effectiveRealm));
            }
            tenantId = headerTenantId;
        } else if (grant.defaultTenantId() != null && usable.contains(grant.defaultTenantId().trim())) {
            tenantId = grant.defaultTenantId().trim();
        } else if (usable.size() == 1) {
            tenantId = usable.get(0);
        } else if (usable.isEmpty()) {
            throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, String.format(
                    "No tenant the binding for application '%s' grants is authorized by the membership in realm '%s'",
                    grant.applicationId(), effectiveRealm));
        } else {
            throw refusal(PrincipalResolutionError.TENANT_SELECTION_REQUIRED, String.format(
                    "The binding for application '%s' reaches %d tenants in realm '%s' with no default; select one",
                    grant.applicationId(), usable.size(), effectiveRealm));
        }
        String selected = tenantId;
        RealmTenantMembership membership = realmMembershipService
                .tenantInRealm(effectiveRealm, selected)
                .orElseThrow(() -> refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Tenant '" + selected + "' is not active in realm '" + effectiveRealm + "'"));
        return withTenant(context, effectiveRealm, selected, assignment, membership);
    }

    /** The context's roles plus those of the bindings granting the selected tenant. */
    private static PrincipalContext withBindingRoles(PrincipalContext context, ApplicationGrant grant) {
        String tenant = context.getDomainContext() == null ? null : context.getDomainContext().getTenantId();
        Set<String> added = grant.rolesFor(tenant);
        if (added.isEmpty()) {
            return context;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : context.getRoles() == null ? new String[0] : context.getRoles()) {
            if (role != null && !"ANONYMOUS".equals(role)) {
                roles.add(role);
            }
        }
        roles.addAll(added);
        return copy(context).withRoles(roles.toArray(String[]::new)).build();
    }

    // ------------------------------------------------------------------ application realm

    /**
     * The realm an identity-only request runs in when it names none: the application decides.
     * A realm the application owns ({@code Realm.applicationRef}) is where its data lives, so:
     *
     * <ul>
     *   <li>The home realm is owned by the application: {@code null}, the home realm, without
     *       reading assignments.</li>
     *   <li>Otherwise the owned realms, through {@link ApplicationRealmContextResolver}, in which
     *       the identity holds an active assignment (status active or unset; not invited or suspended), allowed by its realm
     *       authorization and, when shared, with a tenant it can reach. Exactly one: that realm,
     *       even when the home realm also grants the application (a shared-realm application
     *       reached by identities homed elsewhere). Several: {@code REALM_NOT_AUTHORIZED}; the
     *       realm must be selected explicitly.</li>
     *   <li>None: {@code null}, the home realm, where admission decides (an application that owns
     *       no realm the identity is a member of).</li>
     * </ul>
     */
    String applicationRealm(CredentialUserIdPassword credential, String applicationId) {
        if (blank(applicationId) || realmMembershipService == null) {
            return null;
        }
        String homeRealm = credential.getDomainContext() == null
                ? null : credential.getDomainContext().getDefaultRealm();
        if (homeRealm != null && systemDirectory.findRealmByRefName(homeRealm)
                .map(PrincipalContextResolver::realmApplicationId)
                .filter(applicationId.trim()::equalsIgnoreCase)
                .isPresent()) {
            return null;
        }
        List<UserRealmRole> assignments = realmMembershipService.realmsForUser(credential.getUserId());
        List<UserRealmRole> active = (assignments == null ? List.<UserRealmRole>of() : assignments).stream()
                .filter(Objects::nonNull)
                .filter(PrincipalContextResolver::activeAssignment)
                .filter(assignment -> assignment.getRealmRefName() != null)
                .filter(assignment -> !assignment.getRealmRefName().equals(homeRealm))
                .toList();
        if (active.isEmpty()) {
            return null;
        }
        List<String> allowed = securityUtils.computeAllowedRealmRefNames(credential,
                active.stream().map(UserRealmRole::getRealmRefName).distinct().toList());
        List<Realm> allowedRealms = allowed.stream()
                .map(systemDirectory::findRealmByRefName)
                .flatMap(Optional::stream)
                .toList();
        List<String> owned = ApplicationRealmContextResolver.resolve(
                        applicationId, credential, allowedRealms, active,
                        realmMembershipService::membersOfRealm)
                .stream()
                .map(context -> context.getRealm().getRefName())
                .toList();
        if (owned.isEmpty()) {
            return null;
        }
        if (owned.size() == 1) {
            return owned.get(0);
        }
        throw refusal(PrincipalResolutionError.REALM_NOT_AUTHORIZED, String.format(
                "%s may use application '%s' in several realms %s; the realm must be selected explicitly",
                credential.getUserId(), applicationId, owned));
    }

    /** An assignment that counts as membership: active, or with no status (legacy and seeded). */
    static boolean activeAssignment(UserRealmRole assignment) {
        return assignment.getStatus() == null || UserRealmRole.STATUS_ACTIVE.equals(assignment.getStatus());
    }

    // ------------------------------------------------------------------ realm switch

    /**
     * Applies a realm selection as {@code SecurityFilter} always has. Switching realms keeps the
     * identity and roles and adopts the target realm's DomainContext; the original data domain
     * is kept for audit. Skipped while impersonating. A user's application must be admitted in the
     * realm ({@link #applicationAdmitted}); the credential is read by user id, and never where
     * token claims are trusted.
     */
    public PrincipalContext applyRealmOverride(PrincipalContext context, String realm, boolean trustTokenClaims) {
        return applyRealmOverride(context, realm, trustTokenClaims, false,
                trustTokenClaims ? () -> null : () -> credentialOf(context.getUserId()));
    }

    /**
     * {@code identityOnly}: refusals are typed and the application is not checked here (identity
     * resolution admits the application once, for the final realm, without trusting a token pair).
     */
    PrincipalContext applyRealmOverride(PrincipalContext context, String realm, boolean trustTokenClaims,
                                        boolean identityOnly, Supplier<CredentialUserIdPassword> credential) {
        boolean checkApplication = !identityOnly;
        if (realm == null || realm.isBlank()) {
            return context;
        }
        if (context.getImpersonatedBySubject() != null || context.getImpersonatedByUserId() != null) {
            Log.debugf("Realm override skipped - impersonation is active for user %s", context.getUserId());
            return context;
        }
        // A delegated resource server has no local identity catalog. Repeating the
        // issuer-admitted realm does not request a new realm or application.
        if (trustTokenClaims && realm.equals(context.getDefaultRealm())) {
            return context;
        }
        Realm targetRealm = systemDirectory.findRealmByRefName(realm)
                .orElseThrow(() -> checkApplication
                        ? new jakarta.ws.rs.ForbiddenException("Realm '" + realm + "' is not registered")
                        : refusal(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                                "Realm '" + realm + "' is not registered"));
        String applicationId = context.getApplicationId();
        // Application admission is for identities. A calling service's context already runs in the
        // realm it names (its reach is its own realm authorization), so it was never checked here.
        boolean service = PrincipalContext.SCOPE_SERVICE.equals(context.getScope());
        if (checkApplication && !service && applicationId != null && !applicationId.isBlank()
                && !applicationAdmitted(context.getUserId(), realm, applicationId, credential)) {
            throw new jakarta.ws.rs.ForbiddenException(
                    "Realm '" + realm + "' is not assigned to application '" + applicationId + "'");
        }
        if (realm.equals(context.getDefaultRealm())) {
            return context;
        }
        DomainContext targetDomainContext = targetRealm.getDomainContext();
        if (targetDomainContext == null) {
            throw checkApplication
                    ? new jakarta.ws.rs.ForbiddenException("Realm '" + realm + "' has no data-domain configuration")
                    : refusal(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                            "Realm '" + realm + "' has no data-domain configuration");
        }
        DataDomain effectiveDataDomain = targetDomainContext.toDataDomain(context.getUserId());
        Log.infof("Applying realm override: user=%s, originalRealm=%s, targetRealm=%s, "
                        + "originalDataDomain=%s, effectiveDataDomain=%s",
                context.getUserId(), context.getDefaultRealm(), realm, context.getDataDomain(), effectiveDataDomain);
        return copy(context)
                .withDefaultRealm(realm)
                .withDomainContext(targetDomainContext)
                .withDataDomain(effectiveDataDomain)
                .withRealmOverrideActive(true)
                .withOriginalDataDomain(context.getDataDomain())
                .build();
    }

    private static String realmApplicationId(Realm realm) {
        return realm == null || realm.getApplicationRef() == null ? null : realm.getApplicationRef().getEntityRefName();
    }

    /**
     * The one application admission rule, used by identity resolution, realm selection and
     * application sessions alike: {@code applicationId} is admitted in {@code realm} when it is the
     * realm's own application, or when it is granted to the identity there, either on its realm
     * assignment or by the credential's {@code applicationRegEx}. That is the grant login applies
     * ({@link ApplicationAuthorizationResolver}). No token claim is trusted: a session minted in
     * the realm does not by itself admit an application the identity is not granted.
     *
     * Whether the realm must be registered is the caller's precondition, not part of this rule:
     * an identity's home realm may be served from its credential alone.
     */
    public boolean applicationAdmitted(String userId, String realmRefName, String applicationId,
                                       CredentialUserIdPassword credential) {
        return applicationAdmitted(userId, realmRefName, applicationId, () -> credential);
    }

    /**
     * {@code credential} is read only when neither the realm's ownership nor the assignment admits
     * the application. It yields null where there is no local identity catalog (a resource server
     * trusting token claims), and then the credential pattern grants nothing.
     */
    boolean applicationAdmitted(String userId, String realmRefName, String applicationId,
                                Supplier<CredentialUserIdPassword> credential) {
        if (blank(realmRefName) || blank(applicationId)) {
            return false;
        }
        String owner = systemDirectory.findRealmByRefName(realmRefName)
                .map(PrincipalContextResolver::realmApplicationId).orElse(null);
        if (owner != null && applicationId.equalsIgnoreCase(owner)) {
            return true;
        }
        if (blank(userId)) {
            return false;
        }
        List<String> assigned = realmMembershipService == null ? null
                : realmMembershipService.assignmentForUser(userId, realmRefName)
                        .map(UserRealmRole::getAuthorizedApplications).orElse(null);
        if (ApplicationAuthorizationResolver.resolve(assigned, null, null, applicationId).resolved()) {
            return true;
        }
        CredentialUserIdPassword identity = credential.get();
        String pattern = identity == null ? null : identity.getApplicationRegEx();
        return !blank(pattern)
                && ApplicationAuthorizationResolver.resolve(assigned, pattern, null, applicationId).resolved();
    }

    /**
     * Binary-compatible entry point kept for callers compiled against 1.4.2 and earlier.
     *
     * @deprecated Use {@link #applicationAdmitted(String, String, String, CredentialUserIdPassword)}.
     * This overload now applies the one admission rule and fails closed: it no longer trusts a
     * session minted in the realm ({@code sessionRealm} is ignored), it reads the realm's own
     * application from the directory ({@code realmHomeApplicationId} is ignored), and with no
     * credential to consult only the realm's ownership and the realm assignment admit.
     */
    @Deprecated(since = "1.4.3", forRemoval = true)
    public boolean applicationAdmittedInRealm(String userId, String realmRefName, String applicationId,
                                              String realmHomeApplicationId, String sessionRealm) {
        return applicationAdmitted(userId, realmRefName, applicationId, () -> null);
    }

    private CredentialUserIdPassword credentialOf(String userId) {
        if (credentialRepo == null || envConfigUtils == null || blank(userId)) {
            return null;
        }
        return credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true).orElse(null);
    }

    /**
     * In an application session, a token minted for another application says nothing about this
     * application: the user must be admitted to it in the session realm.
     */
    public void requireSessionApplicationAdmitted(PrincipalContext context, boolean localIdentityCatalog) {
        requireSessionApplicationAdmitted(context,
                localIdentityCatalog ? () -> credentialOf(context.getUserId()) : () -> null);
    }

    /**
     * Binary-compatible entry point kept for callers compiled against 1.4.2 and earlier.
     *
     * @deprecated Use {@link #requireSessionApplicationAdmitted(PrincipalContext, boolean)}. This
     * overload consults no credential (as before): the realm's own application or the realm
     * assignment admits.
     */
    @Deprecated(since = "1.4.3", forRemoval = true)
    public void requireSessionApplicationAdmitted(PrincipalContext context) {
        requireSessionApplicationAdmitted(context, false);
    }

    private void requireSessionApplicationAdmitted(PrincipalContext context,
                                                   Supplier<CredentialUserIdPassword> credential) {
        String applicationId = context.getApplicationId();
        String realmRefName = context.getDefaultRealm();
        Realm realm = systemDirectory.findRealmByRefName(realmRefName).orElse(null);
        if (realm == null || !applicationAdmitted(context.getUserId(), realmRefName, applicationId, credential)) {
            throw new jakarta.ws.rs.ForbiddenException(String.format(
                    "Signed in as %s, no access to application '%s' in realm '%s'",
                    context.getUserId(), applicationId, realmRefName));
        }
    }

    /** Identity-only admission of the resolved realm: {@link #applicationAdmitted}, refused typed. */
    private void requireApplicationGranted(PrincipalContext context, CredentialUserIdPassword credential) {
        if (!applicationAdmitted(context.getUserId(), context.getDefaultRealm(), context.getApplicationId(),
                credential)) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "%s has no access to application '%s' in realm '%s'",
                    context.getUserId(), context.getApplicationId(), context.getDefaultRealm()));
        }
    }

    /** Receiver-side role boundary, shared by credential and trusted-claims requests. */
    public PrincipalContext finalizeUserPrincipal(PrincipalContext context) {
        return finalizeUserPrincipal(context, (CredentialUserIdPassword) null);
    }

    /**
     * As {@link #finalizeUserPrincipal(PrincipalContext)}, with the identity's already-loaded local
     * credential, so the roles of tenant-owned groups follow the tenant the session selected
     * ({@link IdentityRoleResolver#withSessionTenantGroups}). Without one the roles came from a claims
     * provider or a remote directory, which decided them, and are not regrouped.
     */
    public PrincipalContext finalizeUserPrincipal(PrincipalContext context, CredentialUserIdPassword credential) {
        try {
            return finalizeUserPrincipal(context, null, credential);
        } catch (PrincipalResolutionException refused) {
            throw RemotePrincipalResolutionException.of(refused.error(), refused.getMessage());
        }
    }

    private PrincipalContext finalizeUserPrincipal(PrincipalContext context, ApplicationGrant grant,
                                                   CredentialUserIdPassword credential) {
        DomainContext selectedDomain = context.getDomainContext();
        Realm selectedRealm = systemDirectory.findRealmByRefName(context.getDefaultRealm()).orElse(null);
        if (selectedRealm != null && selectedRealm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT
                && selectedRealm.getDomainContext() != null) {
            selectedDomain = selectedRealm.getDomainContext();
        }
        String tenant = selectedDomain == null ? null : selectedDomain.getTenantId();
        if (blank(context.getUserId())) {
            return context;
        }
        PrincipalContext regrouped = withSessionTenantGroups(context, credential, tenant);
        return finalizeTenantRoles(regrouped, grant, selectedDomain, tenant);
    }

    private PrincipalContext finalizeTenantRoles(PrincipalContext context, ApplicationGrant grant,
                                                 DomainContext selectedDomain, String tenant) {
        // Read the complete assignment: the active-only lookup hides suspended outsiders.
        UserRealmRole assignment = realmMembershipService.realmsForUser(context.getUserId()).stream()
                .filter(row -> Objects.equals(context.getDefaultRealm(), row.getRealmRefName()))
                .findFirst().orElse(null);
        if (assignment == null) {
            return context;
        }
        if (blank(tenant)) {
            // No tenant was selected, so nothing says the caller is acting where it is a member. An
            // identity that is an outsider anywhere in this realm then holds only the outsider role.
            boolean outsiderSomewhere = assignment.getExternalTenantIds() != null
                    && assignment.getExternalTenantIds().stream().anyMatch(id -> !blank(id));
            if (!outsiderSomewhere) {
                return context;
            }
            if (!activeAssignment(assignment)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "External membership is not active in this realm");
            }
            return copy(context).withRoles(new String[]{UserRealmRole.ROLE_EXTERNAL}).build();
        }
        if (!assignment.externalInTenant(tenant)) {
            return context;
        }
        boolean explicitlyAuthorized = assignment.getAuthorizedTenantIds() != null
                && assignment.getAuthorizedTenantIds().stream().filter(Objects::nonNull)
                    .map(String::trim).anyMatch(tenant::equals);
        if (!activeAssignment(assignment) || !explicitlyAuthorized) {
            throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                    "External membership no longer authorizes the selected tenant");
        }
        Set<String> roles = new LinkedHashSet<>();
        roles.add(UserRealmRole.ROLE_EXTERNAL);
        if (grant != null) {
            for (BoundTenants bound : grant.bindings()) {
                if (!bound.binding().groupBinding() && bound.tenants().contains(tenant)
                        && bound.binding().getRoles() != null) {
                    bound.binding().getRoles().stream().filter(Objects::nonNull)
                            .map(String::trim).forEach(roles::add);
                }
            }
        }
        PrincipalContext.Builder finalized = copy(context).withRoles(roles.toArray(String[]::new));
        if (!sameDataCoordinate(selectedDomain, context.getDomainContext())) {
            finalized.withDomainContext(selectedDomain).withDataDomain(selectedDomain.toDataDomain(context.getUserId()));
        }
        return finalized.build();
    }

    /**
     * The context's roles for the tenant the session selected: groups another tenant owns never
     * grant here, and groups the selected tenant owns do ({@link IdentityRoleResolver#withSessionTenantGroups}).
     */
    private PrincipalContext withSessionTenantGroups(PrincipalContext context, CredentialUserIdPassword credential,
                                                     String tenant) {
        if (identityRoleResolver == null || credential == null || blank(tenant) || context.getRoles() == null
                || !Objects.equals(credential.getUserId(), context.getUserId())) {
            return context;
        }
        String[] adjusted = identityRoleResolver.withSessionTenantGroups(context.getRoles(), credential,
                context.getDefaultRealm(), context.getApplicationId(), tenant);
        if (java.util.Arrays.equals(adjusted, context.getRoles())) {
            return context;
        }
        return copy(context).withRoles(adjusted).build();
    }

    // ------------------------------------------------------------------ tenant selection

    /**
     * Tenant selection as {@code SecurityFilter} applies it to every principal. A legacy or
     * single-tenant realm derives its tenant from the realm's DomainContext; in a shared realm
     * the tenant is {@code requestedTenantId}, else the token's tenant, checked against the
     * realm assignment and the realm's active memberships.
     */
    public PrincipalContext applyTenantSelection(PrincipalContext context, String requestedTenantId,
                                                 String tokenTenantId, boolean tenantSelectionExempt) {
        // Public endpoints are processed before a principal exists. Do not make their
        // availability depend on the control plane when no tenant was explicitly selected.
        if (blank(requestedTenantId) && Arrays.asList(context.getRoles()).contains("ANONYMOUS")) {
            return context;
        }
        // Catalog reads and service-token mint are platform operations that need no tenant.
        if (blank(requestedTenantId) && tenantSelectionExempt) {
            return context;
        }
        String effectiveRealm = context.getDefaultRealm();
        Optional<Realm> realmOpt = systemDirectory.findRealmByRefName(effectiveRealm);
        if (realmOpt.isEmpty()) {
            if (!blank(requestedTenantId)) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "X-Tenant-Id cannot be resolved because realm '" + effectiveRealm + "' is not registered");
            }
            // A stale token must not keep its projected tenant in a realm that is gone.
            if (!blank(tokenTenantId)) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "Token tenant '" + tokenTenantId.trim() + "' cannot be resolved because realm '"
                                + effectiveRealm + "' is not registered");
            }
            return context;
        }
        Realm realm = realmOpt.get();
        String headerTenantId = requestedTenantId == null ? null : requestedTenantId.trim();
        if (realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT) {
            String defaultTenantId = realm.getDomainContext() == null ? null : realm.getDomainContext().getTenantId();
            if (!blank(headerTenantId) && !headerTenantId.equals(defaultTenantId)) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "X-Tenant-Id does not match the single tenant configured for realm '" + effectiveRealm + "'");
            }
            return context;
        }
        final String tenantId = !blank(headerTenantId)
                ? headerTenantId
                : (tokenTenantId == null ? null : tokenTenantId.trim());
        if (blank(tenantId)) {
            throw new jakarta.ws.rs.BadRequestException(
                    "No tenant for multi-tenant realm '" + effectiveRealm
                            + "': the token carries no tenant and no X-Tenant-Id was selected");
        }
        UserRealmRole assignment = realmMembershipService
                .assignmentForUser(context.getUserId(), effectiveRealm)
                .orElseThrow(() -> new jakarta.ws.rs.ForbiddenException(
                        "Principal has no active assignment in realm '" + effectiveRealm + "'"));
        if (!AccessibleTenantResolver.isAuthorized(assignment, tenantId)) {
            throw new jakarta.ws.rs.ForbiddenException(
                    "Principal is not assigned to tenant '" + tenantId + "' in realm '" + effectiveRealm + "'");
        }
        RealmTenantMembership membership = realmMembershipService
                .tenantInRealm(effectiveRealm, tenantId)
                .orElseThrow(() -> new jakarta.ws.rs.ForbiddenException(
                        "Tenant '" + tenantId + "' is not active in realm '" + effectiveRealm + "'"));
        return withTenant(context, effectiveRealm, tenantId, assignment, membership);
    }

    /**
     * Identity-only tenancy ({@code IDENTITY_ONLY_TOKENS_DESIGN.md}, "Tenancy models"). A
     * dedicated realm is its one tenant. In a shared realm the tenant is the requested one, else
     * the identity's default (credential DomainContext tenant, then data-domain policy default)
     * when the assignment authorizes it, else the one tenant the identity can reach; several
     * tenants and no default fail {@code TENANT_SELECTION_REQUIRED}.
     *
     * <p>Public so an operation that records a principal's tenant (Auth's service-token issue)
     * takes it from this one selection rule rather than from the realm's own data coordinate.
     * Refusals are {@link PrincipalResolutionException}s ({@code TENANT_NOT_AUTHORIZED},
     * {@code TENANT_SELECTION_REQUIRED}).
     */
    public PrincipalContext applyIdentityTenantSelection(PrincipalContext context, String requestedTenantId,
                                                  CredentialUserIdPassword credential) {
        String effectiveRealm = context.getDefaultRealm();
        String headerTenantId = blank(requestedTenantId) ? null : requestedTenantId.trim();
        Optional<Realm> realmOpt = systemDirectory.findRealmByRefName(effectiveRealm);
        if (realmOpt.isEmpty()) {
            if (headerTenantId != null) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Tenant '" + headerTenantId + "' cannot be resolved because realm '" + effectiveRealm
                                + "' is not registered");
            }
            return context;
        }
        Realm realm = realmOpt.get();
        if (realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT) {
            DomainContext realmContext = realm.getDomainContext();
            String realmTenant = realmContext == null ? null : realmContext.getTenantId();
            if (headerTenantId != null && !headerTenantId.equals(realmTenant)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Tenant '" + headerTenantId + "' is not the single tenant of realm '" + effectiveRealm + "'");
            }
            if (realmContext == null || sameDataCoordinate(realmContext, context.getDomainContext())) {
                return context;
            }
            // A dedicated realm is its one tenant: a home DomainContext naming another tenant,
            // organization or account does not carry into it.
            return copy(context)
                    .withDomainContext(realmContext)
                    .withDataDomain(realmContext.toDataDomain(context.getUserId()))
                    .build();
        }

        UserRealmRole assignment = realmMembershipService
                .assignmentForUser(context.getUserId(), effectiveRealm)
                .orElseThrow(() -> refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Principal has no active assignment in realm '" + effectiveRealm + "'"));
        String tenantId;
        if (headerTenantId != null) {
            if (!AccessibleTenantResolver.isAuthorized(assignment, headerTenantId)) {
                throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Principal is not assigned to tenant '" + headerTenantId + "' in realm '" + effectiveRealm + "'");
            }
            tenantId = headerTenantId;
        } else {
            tenantId = defaultTenant(realm, assignment, credential);
        }
        RealmTenantMembership membership = realmMembershipService
                .tenantInRealm(effectiveRealm, tenantId)
                .orElseThrow(() -> refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                        "Tenant '" + tenantId + "' is not active in realm '" + effectiveRealm + "'"));
        return withTenant(context, effectiveRealm, tenantId, assignment, membership);
    }

    private static boolean sameDataCoordinate(DomainContext realmContext, DomainContext current) {
        return current != null
                && Objects.equals(realmContext.getTenantId(), current.getTenantId())
                && Objects.equals(realmContext.getOrgRefName(), current.getOrgRefName())
                && Objects.equals(realmContext.getAccountId(), current.getAccountId());
    }

    private String defaultTenant(Realm realm, UserRealmRole assignment, CredentialUserIdPassword credential) {
        String realmRefName = realm.getRefName();
        Optional<String> declared = TenantDefaults.candidates(credential).stream()
                .filter(tenant -> AccessibleTenantResolver.isAuthorized(assignment, tenant))
                .filter(tenant -> realmMembershipService.tenantInRealm(realmRefName, tenant).isPresent())
                .findFirst();
        if (declared.isPresent()) {
            return declared.get();
        }
        List<AccessibleTenantInfo> reachable = AccessibleTenantResolver.resolve(
                realm, assignment, realmMembershipService.membersOfRealm(realmRefName));
        if (reachable.size() == 1) {
            return reachable.get(0).getTenantId();
        }
        if (reachable.isEmpty()) {
            throw refusal(PrincipalResolutionError.TENANT_NOT_AUTHORIZED,
                    "Principal has no active tenant in realm '" + realmRefName + "'");
        }
        throw refusal(PrincipalResolutionError.TENANT_SELECTION_REQUIRED,
                "Principal can reach " + reachable.size() + " tenants in realm '" + realmRefName
                        + "' and defines no default tenant; select one");
    }

    private static PrincipalContext withTenant(PrincipalContext context, String effectiveRealm, String tenantId,
                                               UserRealmRole assignment, RealmTenantMembership membership) {
        DomainContext tenantContext = DomainContext.builder()
                .defaultRealm(effectiveRealm)
                .tenantId(tenantId)
                .orgRefName(membership.getOrganizationRefName())
                .accountId(membership.getAccountId())
                .dataSegment(0)
                .build();
        // The user's default partition in the realm (its assignment's data domain) refines the
        // membership coordinate when it is for this same tenant/org/account: its data segment and
        // location carry over. Tenant, org and account always come from the membership.
        DataDomain partition = assignment.getDataDomain();
        boolean partitionApplies = partition != null
                && tenantId.equals(partition.getTenantId() == null ? null : partition.getTenantId().trim())
                && Objects.equals(membership.getOrganizationRefName(), partition.getOrgRefName())
                && Objects.equals(membership.getAccountId(), partition.getAccountNum());
        if (partitionApplies) {
            tenantContext.setDataSegment(partition.getDataSegment());
        }
        DataDomain effectiveDataDomain = tenantContext.toDataDomain(context.getUserId());
        if (partitionApplies) {
            effectiveDataDomain.setLocationId(partition.getLocationId());
            effectiveDataDomain.setBusinessTransactionId(partition.getBusinessTransactionId());
        }
        return copy(context)
                .withDefaultRealm(effectiveRealm)
                .withDomainContext(tenantContext)
                .withDataDomain(effectiveDataDomain)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private static PrincipalContext.Builder copy(PrincipalContext context) {
        return new PrincipalContext.Builder()
                .withDefaultRealm(context.getDefaultRealm())
                .withApplicationId(context.getApplicationId())
                .withDomainContext(context.getDomainContext())
                .withDataDomain(context.getDataDomain())
                .withUserId(context.getUserId())
                .withSubjectId(context.getSubjectId())
                .withRoles(context.getRoles())
                .withScope(context.getScope())
                .withArea2RealmOverrides(context.getArea2RealmOverrides())
                .withDataDomainPolicy(context.getDataDomainPolicy())
                .withImpersonatedBySubject(context.getImpersonatedBySubject())
                .withImpersonatedByUserId(context.getImpersonatedByUserId())
                .withActingOnBehalfOfSubject(context.getActingOnBehalfOfSubject())
                .withActingOnBehalfOfUserId(context.getActingOnBehalfOfUserId())
                .withRealmOverrideActive(context.isRealmOverrideActive())
                .withOriginalDataDomain(context.getOriginalDataDomain());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static PrincipalResolutionException refusal(PrincipalResolutionError error, String message) {
        return new PrincipalResolutionException(error, message);
    }

    private static RuntimeException fail(boolean typed, PrincipalResolutionError error, String message,
                                         java.util.function.Function<String, RuntimeException> legacy) {
        return typed ? refusal(error, message) : legacy.apply(message);
    }
}
