package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.rest.models.AccessibleTenantInfo;
import com.e2eq.framework.model.auth.AccessibleTenantResolver;
import com.e2eq.framework.model.auth.ApplicationAuthorizationResolver;
import com.e2eq.framework.model.auth.TenantDefaults;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.security.AccountType;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.ServiceKind;
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
import java.util.List;
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

    ImpersonationScriptEvaluator scriptEvaluator = new ImpersonationScriptEvaluator();

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

    // ------------------------------------------------------------------ resolve

    public PrincipalContextResolution resolve(PrincipalContextRequest request) {
        Objects.requireNonNull(request, "request");
        boolean identity = request.identityOnly();
        if (identity) {
            requireIdentityInputs(request);
        }

        CredentialUserIdPassword caller = findCredential(request);
        if (identity && credentialDisabled(caller)) {
            throw refusal(PrincipalResolutionError.PRINCIPAL_DISABLED,
                    "Credential for '" + caller.getUserId() + "' is " + caller.getActiveStatus());
        }

        if (identity && request.delegationRequested()) {
            return resolveDelegated(request, caller);
        }

        String realm = request.realm();
        PrincipalContext base = buildBaseContext(request, caller, realm);

        CredentialUserIdPassword effective = caller;
        PrincipalRef impersonatedBy = null;
        PrincipalContext context;
        if (!request.impersonationRequested()) {
            context = withoutImpersonation(request, base);
        } else {
            CredentialUserIdPassword original = impersonatorCredential(request);
            String impersonationRealm = identity ? realm : request.signedClaims().impersonationRealm();
            if (identity) {
                validateRealmAccess(original, impersonationRealm, true);
            }
            validateImpersonationPermissions(original, impersonationRealm, identity);
            CredentialUserIdPassword target = findImpersonationTarget(
                    request.impersonateSubject(), request.impersonateUserId())
                    .orElseThrow(() -> identity
                            ? refusal(PrincipalResolutionError.IMPERSONATION_REFUSED,
                                    "Could not find impersonated userId or subject, id:" + impersonationId(request))
                            : new WebApplicationException(String.format(
                                    "Could not find impersonated userId or subject, id:%s", impersonationId(request)),
                                    Response.Status.NOT_FOUND));
            if (identity) {
                requireListedImpersonationTarget(original, target);
            }
            context = buildImpersonatedContext(target, original, request.actingOnBehalfOfSubject(),
                    request.actingOnBehalfOfUserId(), request.applicationId());
            effective = target;
            impersonatedBy = new PrincipalRef(original.getUserId(), original.getSubject());
        }

        context = applyRealmOverride(context, realm, false, identity);
        if (identity) {
            requireApplicationGranted(context, effective);
        } else if (request.signedClaims().foreignApplicationToken()) {
            requireSessionApplicationAdmitted(context);
        }

        if (identity) {
            context = applyIdentityTenantSelection(context, request.tenantId(), effective);
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
        return new PrincipalContextResolution(context, effective, caller, impersonatedBy, null);
    }

    private void requireIdentityInputs(PrincipalContextRequest request) {
        if (!PrincipalContextRequest.nonBlank(request.subject()) && request.userIdCandidates().isEmpty()) {
            throw refusal(PrincipalResolutionError.PRINCIPAL_UNKNOWN, "The token names no subject or userId");
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
                : credentialRepo.findBySubject(sub, systemRealm, true);
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
        return request.identityOnly() && serviceCredential(creds) ? "SERVICE" : "AUTHENTICATED";
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
        if (!scriptEvaluator.evaluate(originalCreds.getSubject(), originalCreds.getUserId(), targetRealm,
                originalCreds.getImpersonateFilterScript())) {
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
        String targetSubject = PrincipalContextRequest.nonBlank(request.actingOnBehalfOfSubject())
                ? request.actingOnBehalfOfSubject().trim() : null;
        String targetUserId = targetSubject == null ? request.actingOnBehalfOfUserId().trim() : null;
        CredentialUserIdPassword target = findImpersonationTarget(targetSubject, targetUserId)
                .orElseThrow(() -> refusal(PrincipalResolutionError.DELEGATION_TARGET_UNKNOWN,
                        "No credential for the acted-for identity"));
        if (credentialDisabled(target)) {
            throw refusal(PrincipalResolutionError.DELEGATION_TARGET_UNKNOWN,
                    "The acted-for identity is " + target.getActiveStatus());
        }

        String targetRealm = request.realm() != null ? request.realm() : target.getDomainContext().getDefaultRealm();
        boolean listed = service.getAuthorizedImpersonateIds() != null
                && !service.getAuthorizedImpersonateIds().isEmpty();
        boolean scripted = service.getImpersonateFilterScript() != null;
        if (!listed && !scripted) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Service credential '" + service.getUserId() + "' has no delegation scope");
        }
        if (listed && !listedTarget(service, target)) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "'" + target.getUserId() + "' is outside the delegation scope of '" + service.getUserId() + "'");
        }
        if (scripted && !scriptEvaluator.evaluate(service.getSubject(), service.getUserId(), targetRealm,
                service.getImpersonateFilterScript())) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Service credential '" + service.getUserId() + "' may not delegate in realm " + targetRealm);
        }
        if (!realmWithinBoundary(service, targetRealm)) {
            throw refusal(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                    "Realm " + targetRealm + " is outside the delegation scope of '" + service.getUserId() + "'");
        }

        PrincipalContextRequest asTarget = PrincipalContextRequest.identity(target.getSubject(), target.getUserId())
                .applicationId(request.applicationId())
                .realm(request.realm())
                .tenantId(request.tenantId())
                .build();
        PrincipalContextResolution resolved = resolve(asTarget);
        return new PrincipalContextResolution(resolved.context(), resolved.credential(), service, null,
                new PrincipalRef(service.getUserId(), service.getSubject()));
    }

    private boolean realmWithinBoundary(CredentialUserIdPassword service, String realm) {
        boolean bounded = (service.getAuthorizedRealms() != null && !service.getAuthorizedRealms().isEmpty())
                || (service.getRealmRegEx() != null && !service.getRealmRegEx().isBlank());
        return !bounded || securityUtils.computeAllowedRealmRefNames(service, List.of(realm)).contains(realm);
    }

    // ------------------------------------------------------------------ realm switch

    /**
     * Applies a realm selection as {@code SecurityFilter} always has. Switching realms keeps the
     * identity and roles and adopts the target realm's DomainContext; the original data domain
     * is kept for audit. Skipped while impersonating. The context's application must belong to
     * the realm or be granted on the user's assignment there.
     */
    public PrincipalContext applyRealmOverride(PrincipalContext context, String realm, boolean trustTokenClaims) {
        return applyRealmOverride(context, realm, trustTokenClaims, false);
    }

    /**
     * {@code identityOnly}: refusals are typed and the application is not checked here (identity
     * resolution admits the application once, for the final realm, without trusting a token pair).
     */
    PrincipalContext applyRealmOverride(PrincipalContext context, String realm, boolean trustTokenClaims,
                                        boolean identityOnly) {
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
        String realmApplicationId = realmApplicationId(targetRealm);
        if (checkApplication && applicationId != null && !applicationId.isBlank()
                && !applicationAdmittedInRealm(context.getUserId(), realm, applicationId, realmApplicationId,
                        context.getDefaultRealm())) {
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
     * Whether {@code applicationId} is the realm's home application or granted on the user's
     * assignment there. When {@code sessionRealm} equals the realm, Auth already minted the
     * session for the application in it, so the verified token pair is trusted.
     */
    public boolean applicationAdmittedInRealm(String userId, String realmRefName, String applicationId,
                                              String realmHomeApplicationId, String sessionRealm) {
        if (realmHomeApplicationId != null && applicationId.equalsIgnoreCase(realmHomeApplicationId)) {
            return true;
        }
        if (sessionRealm != null && sessionRealm.equals(realmRefName)) {
            return true;
        }
        if (userId == null || userId.isBlank() || realmMembershipService == null) {
            return false;
        }
        Optional<UserRealmRole> assignment = realmMembershipService.assignmentForUser(userId, realmRefName);
        if (assignment.isEmpty()) {
            return false;
        }
        List<String> granted = assignment.get().getAuthorizedApplications();
        if (granted == null || granted.isEmpty()) {
            return false;
        }
        return granted.stream().anyMatch(app ->
                app != null && ApplicationAuthorizationResolver.matches(app.trim(), applicationId));
    }

    /**
     * In an application session, a token minted for another application says nothing about this
     * application: the user must be admitted to it in the session realm.
     */
    public void requireSessionApplicationAdmitted(PrincipalContext context) {
        String applicationId = context.getApplicationId();
        String realmRefName = context.getDefaultRealm();
        Realm realm = systemDirectory.findRealmByRefName(realmRefName).orElse(null);
        if (realm == null || !applicationAdmittedInRealm(
                context.getUserId(), realmRefName, applicationId, realmApplicationId(realm), null)) {
            throw new jakarta.ws.rs.ForbiddenException(String.format(
                    "Signed in as %s, no access to application '%s' in realm '%s'",
                    context.getUserId(), applicationId, realmRefName));
        }
    }

    /**
     * Identity-only application admission, the same grant login uses: the realm's home
     * application, or {@link ApplicationAuthorizationResolver} over the realm assignment's
     * applications and the credential's {@code applicationRegEx}. No token pair is trusted.
     */
    private void requireApplicationGranted(PrincipalContext context, CredentialUserIdPassword credential) {
        String applicationId = context.getApplicationId();
        String realmRefName = context.getDefaultRealm();
        Realm realm = systemDirectory.findRealmByRefName(realmRefName).orElse(null);
        if (realm != null && realmApplicationId(realm) != null
                && applicationId.equalsIgnoreCase(realmApplicationId(realm))) {
            return;
        }
        Optional<UserRealmRole> assignment = realmMembershipService == null
                ? Optional.empty()
                : realmMembershipService.assignmentForUser(context.getUserId(), realmRefName);
        ApplicationAuthorizationResolver.Result grant = ApplicationAuthorizationResolver.resolve(
                assignment.map(UserRealmRole::getAuthorizedApplications).orElse(null),
                credential.getApplicationRegEx(),
                null,
                applicationId);
        if (!grant.resolved()) {
            throw refusal(PrincipalResolutionError.APPLICATION_NOT_GRANTED, String.format(
                    "%s has no access to application '%s' in realm '%s'",
                    context.getUserId(), applicationId, realmRefName));
        }
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
     */
    PrincipalContext applyIdentityTenantSelection(PrincipalContext context, String requestedTenantId,
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
            if (realmContext == null || context.getDomainContext() == null
                    || Objects.equals(realmTenant, context.getDomainContext().getTenantId())) {
                return context;
            }
            // A dedicated realm is its one tenant: a home DomainContext naming another tenant
            // does not carry into it.
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

    private String defaultTenant(Realm realm, UserRealmRole assignment, CredentialUserIdPassword credential) {
        String realmRefName = realm.getRefName();
        Optional<String> declared = TenantDefaults.authorizedDefault(credential, assignment)
                .filter(tenant -> realmMembershipService.tenantInRealm(realmRefName, tenant).isPresent());
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
