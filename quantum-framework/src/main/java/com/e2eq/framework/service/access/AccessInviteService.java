package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.AccessInviteRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.CredentialUserIdPassword.RealmEntry;
import com.e2eq.framework.model.security.DataDomainResolution;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.InviteScope;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.requests.AccessInviteScopeClaim;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.service.application.ApplicationRegistryUnavailableException;
import com.e2eq.framework.service.application.ApplicationRegistryValidator;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class AccessInviteService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String ADMIN_ROLE = "admin";
    private static final String SYSTEM_ROLE = "system";

    /** The only flat credential role a user created by an invite receives; invited roles are realm-scoped. */
    private static final String BASELINE_USER_ROLE = "user";

    /** A single concrete realm refName: no wildcard, pattern, list separator, or whitespace. */
    private static final Pattern CONCRETE_REALM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Inject
    AccessInviteRepo inviteRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserRealmRoleRepo userRealmRoleRepo;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    ApplicationRegistryValidator applicationRegistryValidator;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    QuantumModeConfig quantumModeConfig;

    @Inject
    InviteAcceptancePolicies acceptancePolicies;

    @Inject
    com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy.DataDomainResolver dataDomainResolver;

    /** Set once the unique (realmRefName, userId) assignment index is known to exist. */
    private final java.util.concurrent.atomic.AtomicBoolean uniqueAssignmentIndexReady = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whether create/resend return the raw invite token to the inviter. Unset (default): only when
     * no delivering {@link AccessInviteNotificationService} is installed, so with a real delivery
     * channel the token reaches only the invitee. {@code true}/{@code false} force the behavior.
     */
    @ConfigProperty(name = "quantum.access-invites.return-token")
    Optional<Boolean> returnTokenToInviter;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    Instance<AccessInviteProvisioner> provisioners;

    @Inject
    Instance<AccessInviteNotificationService> notificationServices;

    /**
     * Creates an invite into exactly one realm: {@code realm}, the realm of the request context.
     * The inviter must be able to access that realm through its own credential (see
     * {@link #credentialCanAccessRealm}); the request header alone is not trusted. Roles and
     * applications the invite grants apply only in that realm.
     */
    public AccessInviteResponse createInvite(String realm, String inviterUserId, AccessInviteRequest request) {
        if (request == null) {
            throw failure(Code.INVALID_REQUEST, "Invite request body is required.");
        }
        realm = requireSingleRealm(realm, request);
        PrincipalContext caller = requireCaller();
        String requestedRealm = realm;
        Realm realmRecord = registeredRealm(realm).orElseThrow(() -> {
            Log.warnf("access-invite.create.refused reason=realm-not-registered realm=%s", sanitizeForLog(requestedRealm));
            return failure(Code.INVITE_REALM_NOT_ACCESSIBLE, "You cannot invite users into this realm.");
        });
        realm = realmRecord.getRefName();
        String callerUserId = authenticatedUserId(caller);
        if (!isSystemPrincipal(caller) && (normalize(inviterUserId) == null || !normalize(inviterUserId).equals(callerUserId))) {
            throw failure(Code.INVITE_NOT_AUTHORIZED, "An invite can only be created by the signed-in caller.");
        }
        requireRealmAccess(caller, realm);

        String normalizedEmail = normalize(request.getEmail());
        String normalizedTargetUserId = normalize(request.getTargetUserId());
        if (normalizedEmail == null && normalizedTargetUserId == null) {
            throw new BadRequestException("Invite email or target user ID is required.");
        }

        List<String> grantedRoles = normalizeList(request.getGrantedRoles());
        enforceGrantableRoles(caller, realm, inviterUserId, grantedRoles);
        List<String> applications = normalizeApplications(request.getAuthorizedApplications());
        enforceGrantableApplications(caller, realm, inviterUserId, applications);

        // The acceptance policy runs before the application provisioners. The scope is the
        // inviter's partition resolved from its verified principal; the body can only echo it.
        final String inviteRealm = realm;
        InviteAcceptancePolicy policy = inSystemScope(() -> acceptancePolicies.forCreate(realmRecord, request.getAcceptancePolicy()));
        InviteScope scope = snapshotScope(caller, realm);
        requireScopeClaimMatches(realm, callerUserId, request.getScope(), scope);
        InviteCreation creation = new InviteCreation(
            realmRecord,
            caller,
            callerUserId,
            isSystemPrincipal(caller),
            isRealmAdministrator(caller, realm),
            activeAssignment(callerUserId, realm).orElse(null),
            scope,
            request
        );
        DataDomain recordDataDomain = inSystemScope(() -> {
            policy.validateCreate(creation);
            return policy.inviteRecordDataDomain(creation);
        });

        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.validateInviteCreation(realm, inviterUserId, request);
        }

        List<String> scopeRefNames = resolveScopeRefNames(request);
        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.validateScopes(realm, inviterUserId, scopeRefNames);
        }

        ensureNoPendingDuplicate(realm, normalizedEmail, normalizedTargetUserId);

        String rawToken = generateToken();
        String tokenHash = hashToken(rawToken);
        AccessInvite invite = AccessInvite.builder()
            .refName("invite-" + UUID.randomUUID())
            .displayName("Invite " + (normalizedEmail != null ? normalizedEmail : normalizedTargetUserId))
            // The invited identity is fixed here and is the only identity acceptance may bind to.
            .email(normalizedEmail)
            .targetUserId(normalizedTargetUserId)
            .invitedByUserId(normalize(inviterUserId))
            .tokenHash(tokenHash)
            .tokenHint(tokenHint(tokenHash))
            .scopeRefs(toScopeReferences(scopeRefNames))
            .grantedRoles(grantedRoles)
            .authorizedApplications(applications.isEmpty() ? null : applications)
            .issuedBySystemPrincipal(isSystemPrincipal(caller))
            .acceptancePolicy(policy.id())
            .scope(scope)
            .allowedFunctionalAreas(normalizeList(request.getAllowedFunctionalAreas()))
            .allowedFunctionalDomains(normalizeList(request.getAllowedFunctionalDomains()))
            .allowedActions(normalizeList(request.getAllowedActions()))
            .inviteMessage(request.getInviteMessage())
            .expiresAt(Date.from(Instant.now().plus(defaultExpiryDays(request.getExpiresInDays()), ChronoUnit.DAYS)))
            .status(AccessInvite.Status.PENDING)
            .dataDomain(recordDataDomain != null ? recordDataDomain : buildDataDomain(inviterUserId))
            .build();

        invite = inviteRepo.save(inviteRealm, invite);
        for (AccessInviteNotificationService notificationService : notificationServices) {
            notificationService.sendInviteNotification(realm, invite, rawToken);
        }
        return toResponse(invite, tokenForInviter(rawToken));
    }

    /**
     * Lists invites in the realm. Administrators see every invite they may manage (in a pooled
     * realm only those for tenants their realm assignment authorizes); any other caller sees only
     * the invites it created. Tokens are never included.
     */
    public List<AccessInviteResponse> listInvites(String realm) {
        PrincipalContext caller = requireCaller();
        InviteManagement management = inviteManagement(caller, realm);
        String callerUserId = normalize(caller.getUserId());
        return inviteRepo.getAllListIgnoreRules(realm).stream()
            .filter(invite -> (callerUserId != null && callerUserId.equalsIgnoreCase(invite.getInvitedByUserId()))
                || management.mayManage(invite))
            .map(invite -> toResponse(invite, null))
            .toList();
    }

    /**
     * What a caller may administer among a realm's invites. In a dedicated realm a realm
     * administrator manages every invite. In a pooled realm (several tenants) realm-scoped admin
     * roles do not cross tenants: an administrator manages only invites whose scope tenant its own
     * realm assignment authorizes; an invite with no recorded scope only the system principal
     * manages. The system principal manages everything.
     */
    private record InviteManagement(boolean systemPrincipal, boolean admin, boolean pooled, UserRealmRole assignment) {
        boolean mayManage(AccessInvite invite) {
            if (systemPrincipal) {
                return true;
            }
            if (!admin) {
                return false;
            }
            if (!pooled) {
                return true;
            }
            String tenant = invite.getScope() == null || invite.getScope().getDataDomain() == null
                ? null : invite.getScope().getDataDomain().getTenantId();
            return tenant != null && com.e2eq.framework.model.auth.AccessibleTenantResolver.isAuthorized(assignment, tenant);
        }
    }

    private InviteManagement inviteManagement(PrincipalContext caller, String realm) {
        boolean system = isSystemPrincipal(caller);
        boolean admin = isRealmAdministrator(caller, realm);
        boolean pooled = registeredRealm(realm).map(InviteAcceptancePolicies::pooled).orElse(true);
        UserRealmRole assignment = admin && pooled && !system
            ? activeAssignment(authenticatedUserId(caller), realm).orElse(null)
            : null;
        return new InviteManagement(system, admin, pooled, assignment);
    }

    public AccessInviteResponse getInvite(String realm, String refName) {
        AccessInvite invite = requireManageableInvite(realm, refName, "read");
        return toResponse(invite, null);
    }

    public AccessInviteResponse revokeInvite(String realm, String refName) {
        AccessInvite invite = requireManageableInvite(realm, refName, "revoke");
        AccessInvite revoked = inviteRepo.revokePending(realm, invite.getId())
            .orElseThrow(() -> failure(Code.INVITE_NOT_PENDING, "Only a pending invite can be revoked."));
        return toResponse(revoked, null);
    }

    /**
     * Re-issues a pending invite: rotates its token (the previous token stops working), renews the
     * expiry, and notifies the invitee again. The new raw token is returned only to the caller.
     */
    public AccessInviteResponse resendInvite(String realm, String refName, Integer expiresInDays) {
        AccessInvite invite = requireManageableInvite(realm, refName, "resend");
        if (invite.getStatus() != AccessInvite.Status.PENDING) {
            throw failure(Code.INVITE_NOT_PENDING, "Only a pending invite can be resent.");
        }
        String rawToken = generateToken();
        String newHash = hashToken(rawToken);
        Date newExpiry = Date.from(Instant.now().plus(defaultExpiryDays(expiresInDays), ChronoUnit.DAYS));
        AccessInvite rotated = inviteRepo.rotatePendingToken(realm, invite.getId(), invite.getTokenHash(), newHash, tokenHint(newHash), newExpiry)
            .orElseThrow(() -> failure(Code.INVITE_NOT_PENDING, "Invite changed before it could be resent; refresh and retry."));
        for (AccessInviteNotificationService notificationService : notificationServices) {
            notificationService.sendInviteNotification(realm, rotated, rawToken);
        }
        return toResponse(rotated, tokenForInviter(rawToken));
    }

    /**
     * Accepts an invite. The account identity always comes from the invite record (its
     * {@code targetUserId}, else its {@code email}); the request can never choose it.
     * <ul>
     *   <li>A body {@code userId}/{@code email} that differs from the invite is refused and logged.</li>
     *   <li>If the invited identity already has an account, the caller must be signed in as that
     *       identity; otherwise the call is refused with {@code INVITE_SIGN_IN_REQUIRED} and no
     *       access is attached.</li>
     *   <li>If no account exists, one is created for the invited identity only.</li>
     *   <li>The token is single-use: the invite is claimed atomically before provisioning.</li>
     * </ul>
     */
    public AccessInviteAcceptResponse acceptInvite(AccessInviteAcceptRequest request) {
        if (request == null) {
            throw failure(Code.INVALID_REQUEST, "Invite acceptance request body is required.");
        }
        Optional<PrincipalContext> principal = SecurityContext.getPrincipalContext();
        String requestedRealm = trimToNull(request.getRealm());
        if (requestedRealm == null) {
            requestedRealm = principal.map(pctx -> trimToNull(pctx.getDefaultRealm())).orElse(null);
        }
        if (requestedRealm == null) {
            throw failure(Code.INVALID_REQUEST, "Invite acceptance requires the target realm.");
        }
        String presentedRealm = requestedRealm;
        // Only a registered realm is ever opened, under its registered spelling (an anonymous caller
        // must not be able to make the service open datastores for arbitrary names).
        Realm realmRecord = (CONCRETE_REALM.matcher(requestedRealm).matches() ? registeredRealm(requestedRealm) : Optional.<Realm>empty())
            .orElseThrow(() -> {
                Log.warnf("access-invite.accept.refused reason=realm-not-registered realm=%s", sanitizeForLog(presentedRealm));
                return failure(Code.INVITE_TOKEN_INVALID, "Invite token is invalid.");
            });
        String realm = realmRecord.getRefName();

        String presentedHash = hashToken(request.getToken());
        AccessInvite invite = inviteRepo.findByTokenHash(realm, presentedHash)
            .filter(candidate -> constantTimeEquals(candidate.getTokenHash(), presentedHash))
            .orElseThrow(() -> failure(Code.INVITE_TOKEN_INVALID, "Invite token is invalid."));
        expireInviteIfNeeded(realm, invite);
        if (invite.getStatus() != AccessInvite.Status.PENDING) {
            throw failure(Code.INVITE_NOT_PENDING, "Invite is no longer pending.");
        }

        final String invitedUserId = invitedUserId(invite);
        final String invitedEmail = normalize(invite.getEmail());
        final String finalRealm = realm;
        String authenticatedUserId = principal.map(this::authenticatedUserId).orElse(null);

        rejectIdentityClaims(realm, invite, invitedUserId, invitedEmail, request, authenticatedUserId);

        if (principal.isPresent() && authenticatedUserId != null && isDelegated(principal.get())) {
            auditRefusal(realm, invite, "delegated-session", authenticatedUserId, null);
            throw failure(Code.INVITE_IDENTITY_MISMATCH, "An invite cannot be accepted from an impersonated or delegated session.");
        }
        if (authenticatedUserId != null && !authenticatedUserId.equalsIgnoreCase(invitedUserId)) {
            auditRefusal(realm, invite, "session", authenticatedUserId, authenticatedUserId);
            throw failure(Code.INVITE_IDENTITY_MISMATCH, "The signed-in user is not the user this invite was issued to.");
        }

        // Match the identity the way the unique userId index does (case-insensitive) and use the
        // stored spelling from then on.
        Optional<String> existingUserId = inSystemScope(
            () -> credentialRepo.findByUserIdIgnoreCaseIgnoreRules(invitedUserId, systemRealm()).map(CredentialUserIdPassword::getUserId)
        );
        final boolean accountExists = existingUserId.isPresent();
        final String accountUserId = existingUserId.orElse(invitedUserId);
        if (accountExists && authenticatedUserId == null) {
            throw failure(Code.INVITE_SIGN_IN_REQUIRED, "An account already exists for this invite. Sign in as that account, then accept the invite.");
        }
        if (!accountExists && (request.getPassword() == null || request.getPassword().isBlank())) {
            throw failure(Code.INVALID_REQUEST, "A password is required when accepting an invite for a new user.");
        }

        // The inviter's authority is re-checked at acceptance: an inviter that has since lost access
        // to the realm, or the roles it granted, can no longer hand them out.
        requireInviterStillEntitled(realm, invite, existingUserId.orElse(null));
        if (accountExists) {
            CredentialUserIdPassword existingAccount = credentialOf(accountUserId).orElse(null);
            if (existingAccount != null && wouldShadowRealmRegEx(existingAccount, realm)) {
                throw failure(Code.INVITE_ACCOUNT_REALM_CONFLICT,
                    "This account's realm access is defined by a pattern that does not include this realm. An administrator must grant the realm.");
            }
        }

        // The policy is selected before the claim (an unavailable policy leaves the invite pending
        // untouched); it decides the grant after the claim, so any failure is rolled back.
        InviteAcceptancePolicy policy = inSystemScope(() -> acceptancePolicies.forAccept(realmRecord, invite));

        AccessInvite claimed = inviteRepo.claimPending(realm, invite.getId(), invite.getTokenHash(), new Date(), accountUserId)
            .orElseThrow(() -> failure(Code.INVITE_NOT_PENDING, "Invite is no longer pending."));

        final String firstName = request.getFirstName();
        final String lastName = request.getLastName();
        final String password = accountExists ? null : request.getPassword();
        try {
            return SecurityCallScope.runWithContexts(
                securityUtils.getSystemPrincipalContext(),
                securityUtils.getSystemSecurityResourceContext(),
                () -> acceptInviteInSystemScope(
                    realmRecord,
                    policy,
                    claimed,
                    accountUserId,
                    invitedEmail != null ? invitedEmail : invitedUserId,
                    accountExists,
                    firstName,
                    lastName,
                    password
                )
            );
        }
        catch (RuntimeException e) {
            inviteRepo.releaseClaim(finalRealm, claimed.getId(), claimed.getTokenHash(), accountUserId);
            if (e instanceof AccessInviteException || e instanceof WebApplicationException) {
                // Typed refusals (including provisioner validation errors) keep their status.
                throw e;
            }
            Log.errorf(e, "access-invite.accept.failed realm=%s invite=%s", finalRealm, claimed.getRefName());
            throw failure(Code.INVITE_PROVISIONING_FAILED, "The invite could not be accepted. Try again or contact the inviter.");
        }
    }

    private AccessInviteAcceptResponse acceptInviteInSystemScope(
        Realm realmRecord,
        InviteAcceptancePolicy policy,
        AccessInvite invite,
        String invitedUserId,
        String invitedEmail,
        boolean accountExists,
        String firstName,
        String lastName,
        String password
    ) {
        // Everything this acceptance grants is undone if any later step fails, so a released
        // (still pending) invite never leaves access behind.
        String realm = realmRecord.getRefName();
        GrantRollback rollback = new GrantRollback(realm);
        CredentialUserIdPassword credential;
        UserProfile profile;
        InviteGrant grant;
        try {
            CredentialUserIdPassword existingAccount = accountExists
                ? credentialRepo.findByUserIdIgnoreCaseIgnoreRules(invitedUserId, systemRealm())
                    .orElseThrow(() -> failure(Code.INVITE_PROVISIONING_FAILED, "The invite could not be accepted. Try again or contact the inviter."))
                : null;
            // The policy re-runs its create-time entitlement check against the recorded scope.
            CredentialUserIdPassword inviter = invite.isIssuedBySystemPrincipal() ? null : credentialOf(invite.getInvitedByUserId()).orElse(null);
            boolean inviterAdmin = inviter != null && credentialCanAccessRealm(inviter, realm) && rolesInRealm(inviter, realm).contains(ADMIN_ROLE);
            UserRealmRole inviterAssignment = inviter == null ? null : activeAssignment(inviter.getUserId(), realm).orElse(null);
            UserRealmRole inviteeAssignment = accountExists ? assignment(existingAccount.getUserId(), realm).orElse(null) : null;
            grant = policy.resolveGrant(new InviteAcceptance(
                realmRecord, invite, invitedUserId, accountExists, existingAccount, inviterAdmin, inviterAssignment, inviteeAssignment));
            requireGrantWithinInvite(realmRecord, invite, policy, grant, accountExists);

            if (accountExists) {
                CredentialUserIdPassword existing = existingAccount;
                rollback.captureAssignment(existing.getUserId());
                rollback.written = ensureRealmAssignment(realm, existing, grant);
                rollback.realmEntryAdded = !hasExplicitRealmEntry(existing, realm);
                rollback.userId = existing.getUserId();
                credential = ensureUserCredential(realm, invitedUserId, true, null, null);
                rollback.realmEntryAdded = rollback.realmEntryAdded && hasExplicitRealmEntry(credential, realm);
            }
            else {
                credential = ensureUserCredential(realm, invitedUserId, false, password, grant.getHomeContext());
                rollback.createdCredential = credential;
                rollback.userId = credential.getUserId();
                rollback.captureAssignment(credential.getUserId());
                rollback.written = ensureRealmAssignment(realm, credential, grant);
            }
            boolean profileExisted = userProfileRepo.getByUserIdWithIgnoreRules(realm, invitedUserId).isPresent();
            profile = ensureUserProfile(realm, credential, invitedUserId, invitedEmail, firstName, lastName, grant.getProfileDataDomain());
            if (!profileExisted) {
                rollback.createdProfile = profile;
            }
            if (!accountExists) {
                // Sign-in requires a directory profile in the system realm for a USER account
                // (CustomTokenAuthProvider refuses a "half-created" account), exactly as tenant
                // provisioning creates one. Without it the new invitee could not log in.
                rollback.createdSystemProfile = ensureDirectoryProfile(credential, invitedUserId, invitedEmail, firstName, lastName, grant.getHomeContext());
            }
            for (AccessInviteProvisioner provisioner : provisioners) {
                provisioner.onInviteAccepted(realm, invite, credential, profile);
            }
        }
        catch (RuntimeException e) {
            rollback.run();
            throw e;
        }

        return AccessInviteAcceptResponse.builder()
            .userId(invitedUserId)
            .email(invitedEmail)
            .defaultRealm(realm)
            .inviteRefName(invite.getRefName())
            .grantedScopes(invite.getScopeRefs() == null ? List.of() : invite.getScopeRefs().stream().map(EntityReference::getEntityRefName).toList())
            .tenantId(grant.getTenantId())
            .build();
    }

    private UserProfile ensureUserProfile(
        String realm,
        CredentialUserIdPassword credential,
        String userId,
        String email,
        String firstName,
        String lastName,
        DataDomain profileDataDomain
    ) {
        Optional<UserProfile> existing = userProfileRepo.getByUserIdWithIgnoreRules(realm, userId);
        if (existing.isPresent()) {
            UserProfile profile = existing.get();
            if ((profile.getEmail() == null || profile.getEmail().isBlank()) && email != null) {
                profile.setEmail(email);
                profile = userProfileRepo.save(realm, profile);
            }
            return profile;
        }

        UserProfile profile = UserProfile.builder()
            .credentialUserIdPasswordRef(credential.createEntityReference())
            .userId(userId)
            .email(email)
            .fname(firstName)
            .lname(lastName)
            .displayName(buildDisplayName(userId, firstName, lastName, email))
            .dataDomain(profileDataDomain != null ? profileDataDomain : buildDataDomain(userId))
            .build();
        return userProfileRepo.save(realm, profile);
    }

    /**
     * The system-realm directory profile of an account this acceptance created (the login
     * precondition tenant provisioning also satisfies), placed in the account's home context.
     * Returns the profile it created, or null when one already existed.
     */
    private UserProfile ensureDirectoryProfile(
        CredentialUserIdPassword credential,
        String userId,
        String email,
        String firstName,
        String lastName,
        DomainContext homeContext
    ) {
        String systemRealm = systemRealm();
        if (userProfileRepo.getByUserIdWithIgnoreRules(systemRealm, credential.getUserId()).isPresent()) {
            return null;
        }
        DomainContext home = homeContext != null ? homeContext : credential.getDomainContext();
        if (home == null) {
            throw new IllegalStateException("A new account has no home context for its directory profile");
        }
        UserProfile profile = UserProfile.builder()
            .refName(credential.getUserId())
            .credentialUserIdPasswordRef(credential.createEntityReference(systemRealm))
            .userId(credential.getUserId())
            .email(email)
            .fname(firstName)
            .lname(lastName)
            .displayName(buildDisplayName(userId, firstName, lastName, email))
            .dataDomain(home.toDataDomain(credential.getUserId()))
            .build();
        return userProfileRepo.save(systemRealm, profile);
    }

    private CredentialUserIdPassword ensureUserCredential(
        String realm,
        String userId,
        boolean accountExists,
        String password,
        DomainContext homeContext
    ) {
        Optional<CredentialUserIdPassword> existing = credentialRepo.findByUserIdIgnoreCaseIgnoreRules(userId, systemRealm());
        if (existing.isPresent()) {
            if (!accountExists) {
                // The account appeared between the pre-check and the claim; never attach access to an
                // account whose owner has not proven its identity.
                throw failure(Code.INVITE_SIGN_IN_REQUIRED, "An account already exists for this invite. Sign in as that account, then accept the invite.");
            }
            CredentialUserIdPassword credential = existing.get();
            ensureRealmAuthorization(credential, realm);
            return credentialRepo.save(securityUtils.getSystemPrincipalContext().getDefaultRealm(), credential);
        }
        if (accountExists) {
            throw failure(Code.INVITE_PROVISIONING_FAILED, "The invite could not be accepted. Try again or contact the inviter.");
        }

        if (password == null || password.isBlank()) {
            throw new BadRequestException("A password is required when accepting an invite for a new user.");
        }

        // Flat credential roles apply in every realm the user can access, so an invite never puts
        // its roles here; they are written to the realm-scoped UserRealmRole instead. The new
        // account gets only the baseline role every signed-in user needs.
        Set<String> roles = new LinkedHashSet<>();
        roles.add(BASELINE_USER_ROLE);

        if (homeContext == null) {
            throw new IllegalStateException("The acceptance policy returned no home context for a new account");
        }
        DomainContext domainContext = homeContext;
        try {
            authProviderFactory.getUserManager().createUser(
                userId,
                password,
                false,
                roles,
                domainContext
            );
        }
        catch (RuntimeException e) {
            if (credentialRepo.findByUserIdIgnoreCaseIgnoreRules(userId, systemRealm()).isPresent()) {
                // Lost a race with another account creation for the same identity.
                throw failure(Code.INVITE_SIGN_IN_REQUIRED, "An account already exists for this invite. Sign in as that account, then accept the invite.");
            }
            throw e;
        }

        CredentialUserIdPassword credential = credentialRepo.findByUserId(userId, securityUtils.getSystemPrincipalContext().getDefaultRealm(), true)
            .orElseThrow(() -> new IllegalStateException("User created but credential was not found for userId: " + userId));

        if (credential.getDomainContext() == null) {
            credential.setDomainContext(domainContext);
        }
        ensureRealmAuthorization(credential, realm);
        return credentialRepo.save(securityUtils.getSystemPrincipalContext().getDefaultRealm(), credential);
    }

    private void ensureRealmAuthorization(CredentialUserIdPassword credential, String realm) {
        boolean noExplicitList = credential.getAuthorizedRealms() == null || credential.getAuthorizedRealms().isEmpty();
        if (noExplicitList && realmRegExGrants(credential.getRealmRegEx(), realm)) {
            // The realmRegEx already grants this realm. Adding an explicit list entry would shadow the
            // pattern (an explicit list takes precedence over realmRegEx) and cut off its other realms.
            return;
        }
        if (wouldShadowRealmRegEx(credential, realm)) {
            throw failure(Code.INVITE_ACCOUNT_REALM_CONFLICT,
                "This account's realm access is defined by a pattern that does not include this realm. An administrator must grant the realm.");
        }
        List<RealmEntry> authorizedRealms = credential.getAuthorizedRealms() == null
            ? new ArrayList<>()
            : new ArrayList<>(credential.getAuthorizedRealms());
        boolean alreadyPresent = authorizedRealms.stream()
            .anyMatch(entry -> entry != null && realm.equalsIgnoreCase(entry.getRealmRefName()));
        if (!alreadyPresent) {
            RealmEntry entry = new RealmEntry();
            entry.setRealmRefName(realm);
            entry.setRealmDisplayName(realm);
            authorizedRealms.add(entry);
            credential.setAuthorizedRealms(authorizedRealms);
        }
    }

    private DataDomain buildDataDomain(String ownerId) {
        try {
            DataDomain dataDomain = securityUtils.getDefaultDataDomain().clone();
            dataDomain.setOwnerId(normalize(ownerId) != null ? normalize(ownerId) : securityUtils.getSystemPrincipalContext().getUserId());
            return dataDomain;
        }
        catch (CloneNotSupportedException e) {
            throw new IllegalStateException("Unable to clone the default data domain for invite processing.", e);
        }
    }

    /** The identity an invite was issued to: its target user ID, else its invited email. */
    private String invitedUserId(AccessInvite invite) {
        String targetUserId = normalize(invite.getTargetUserId());
        if (targetUserId != null) {
            return targetUserId;
        }
        String email = normalize(invite.getEmail());
        if (email != null) {
            return email;
        }
        throw new IllegalStateException("Invite " + invite.getRefName() + " has no bound identity.");
    }

    /**
     * Refuses any client-supplied identity that differs from the invite. The body may echo the
     * invited identity (older clients send it) but can never change it.
     */
    private void rejectIdentityClaims(
        String realm,
        AccessInvite invite,
        String invitedUserId,
        String invitedEmail,
        AccessInviteAcceptRequest request,
        String authenticatedUserId
    ) {
        String requestedUserId = normalize(request.getUserId());
        if (requestedUserId != null && !requestedUserId.equals(invitedUserId)) {
            auditRefusal(realm, invite, "userId", authenticatedUserId, requestedUserId);
            throw failure(Code.INVITE_IDENTITY_MISMATCH, "The userId in the request does not match this invite.");
        }
        String requestedEmail = normalize(request.getEmail());
        String expectedEmail = invitedEmail != null ? invitedEmail : invitedUserId;
        if (requestedEmail != null && !requestedEmail.equals(expectedEmail)) {
            auditRefusal(realm, invite, "email", authenticatedUserId, requestedEmail);
            throw failure(Code.INVITE_IDENTITY_MISMATCH, "The email in the request does not match this invite.");
        }
    }

    private void auditRefusal(String realm, AccessInvite invite, String field, String authenticatedUserId, String presentedValue) {
        Log.warnf(
            "access-invite.accept.refused reason=identity-mismatch realm=%s invite=%s field=%s authenticatedUser=%s presented=%s",
            realm,
            invite.getRefName(),
            field,
            authenticatedUserId == null ? "<anonymous>" : sanitizeForLog(authenticatedUserId),
            sanitizeForLog(presentedValue)
        );
    }

    private boolean isDelegated(PrincipalContext principal) {
        return normalize(principal.getImpersonatedByUserId()) != null
            || normalize(principal.getImpersonatedBySubject()) != null
            || normalize(principal.getActingOnBehalfOfUserId()) != null
            || normalize(principal.getActingOnBehalfOfSubject()) != null;
    }

    private String authenticatedUserId(PrincipalContext principal) {
        return normalizeAuthenticatedUserId(principal.getUserId());
    }

    private PrincipalContext requireCaller() {
        PrincipalContext caller = SecurityContext.getPrincipalContext().orElse(null);
        if (caller == null || authenticatedUserId(caller) == null) {
            throw failure(Code.INVITE_NOT_AUTHORIZED, "An authenticated caller is required.");
        }
        return caller;
    }

    /** The framework's own system principal (seeders, jobs, internal services). */
    private boolean isSystemPrincipal(PrincipalContext caller) {
        String systemUserId = normalize(securityUtils.getEnvConfigUtils().getSystemUserId());
        String callerUserId = normalize(caller.getUserId());
        if (systemUserId == null || !systemUserId.equals(callerUserId) || caller.getRoles() == null) {
            return false;
        }
        for (String role : caller.getRoles()) {
            if (SYSTEM_ROLE.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Roles {@code credential} holds in {@code realm}: its flat credential roles (which apply in every
     * realm the user can access, by design) plus its active realm-scoped {@link UserRealmRole} roles
     * for that realm. Callers must check realm access separately.
     */
    private Set<String> rolesInRealm(CredentialUserIdPassword credential, String realm) {
        Set<String> roles = new LinkedHashSet<>();
        if (credential == null || realm == null) {
            return roles;
        }
        if (credential.getRoles() != null) {
            addNormalized(roles, List.of(credential.getRoles()));
        }
        activeAssignment(credential.getUserId(), realm).ifPresent(assignment -> addNormalized(roles, assignment.getRoles()));
        return roles;
    }

    /** The caller's credential, looked up in the system realm (case-insensitive, like the unique userId index). */
    private Optional<CredentialUserIdPassword> credentialOf(String userId) {
        String normalized = normalize(userId);
        if (normalized == null) {
            return Optional.empty();
        }
        return inSystemScope(() -> credentialRepo.findByUserIdIgnoreCaseIgnoreRules(normalized, systemRealm()));
    }

    /** The user's assignment for exactly this realm, regardless of status. */
    private Optional<UserRealmRole> assignment(String userId, String realm) {
        if (userId == null || realm == null) {
            return Optional.empty();
        }
        return inSystemScope(() -> realmMembershipService.realmsForUser(userId).stream()
            .filter(candidate -> candidate != null && realm.equalsIgnoreCase(candidate.getRealmRefName()))
            .findFirst());
    }

    private Optional<UserRealmRole> activeAssignment(String userId, String realm) {
        return assignment(userId, realm).filter(candidate -> !UserRealmRole.STATUS_SUSPENDED.equals(candidate.getStatus()));
    }

    private boolean isSystemRealm(String realm) {
        String systemRealm = systemRealm();
        return realm != null && systemRealm != null && realm.trim().equalsIgnoreCase(systemRealm.trim());
    }

    /**
     * Whether {@code credential} grants access to {@code realm}. The system realm is never granted
     * here (only the framework system principal reaches it). Otherwise the realm must be:
     * <ul>
     *   <li>listed in {@code authorizedRealms}, or</li>
     *   <li>matched in full by {@code realmRegEx} ({@code *} alone means every realm), or</li>
     *   <li>the credential's home realm ({@code domainContext.defaultRealm}), which the framework's
     *       realm-access contract always authorizes.</li>
     * </ul>
     * Comparison is case-insensitive.
     */
    private boolean credentialCanAccessRealm(CredentialUserIdPassword credential, String realm) {
        if (credential == null || realm == null || realm.isBlank() || isSystemRealm(realm)) {
            return false;
        }
        String target = realm.trim();
        String homeRealm = credential.getDomainContext() != null ? credential.getDomainContext().getDefaultRealm() : null;
        if (homeRealm != null && target.equalsIgnoreCase(homeRealm.trim())) {
            return true;
        }
        if (credential.getAuthorizedRealms() != null && !credential.getAuthorizedRealms().isEmpty()) {
            // An explicit list takes precedence over realmRegEx, exactly as at sign-in
            // (SecurityUtils.computeAllowedRealmRefNames): the pattern is not consulted.
            return hasExplicitRealmEntry(credential, target);
        }
        return realmRegExGrants(credential.getRealmRegEx(), target);
    }

    private static boolean hasExplicitRealmEntry(CredentialUserIdPassword credential, String realm) {
        if (credential == null || credential.getAuthorizedRealms() == null || realm == null) {
            return false;
        }
        for (RealmEntry entry : credential.getAuthorizedRealms()) {
            if (entry != null && entry.getRealmRefName() != null && realm.trim().equalsIgnoreCase(entry.getRealmRefName().trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when attaching {@code realm} to this account would replace its realmRegEx with an explicit
     * list (the list takes precedence) and so silently cut it off from the realms the pattern grants.
     */
    private boolean wouldShadowRealmRegEx(CredentialUserIdPassword credential, String realm) {
        boolean noExplicitList = credential.getAuthorizedRealms() == null || credential.getAuthorizedRealms().isEmpty();
        String pattern = credential.getRealmRegEx() == null ? null : credential.getRealmRegEx().trim();
        return noExplicitList && pattern != null && !pattern.isEmpty()
            && !realmRegExGrants(pattern, realm) && !credentialCanAccessRealm(credential, realm);
    }

    private Optional<Realm> registeredRealm(String realm) {
        if (realm == null) {
            return Optional.empty();
        }
        return inSystemScope(() -> {
            Optional<Realm> found = systemDirectory.findRealmByRefName(realm);
            if (found.isEmpty() && !realm.equals(realm.toLowerCase(Locale.ROOT))) {
                found = systemDirectory.findRealmByRefName(realm.toLowerCase(Locale.ROOT));
            }
            return found.filter(candidate -> candidate.getRefName() != null && candidate.getRefName().equalsIgnoreCase(realm));
        });
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Full-string, case-insensitive match of {@code realmRegEx} against {@code realm}, using the same
     * {@code *} wildcard convention as {@code SecurityUtils.computeAllowedRealmRefNames}. Never matches
     * a substring; an invalid pattern grants nothing.
     */
    private boolean realmRegExGrants(String realmRegEx, String realm) {
        if (realmRegEx == null || realm == null || isSystemRealm(realm)) {
            return false;
        }
        String pattern = realmRegEx.trim();
        if (pattern.isEmpty()) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        try {
            return Pattern.compile(pattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE).matcher(realm.trim()).matches();
        }
        catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Requires that the caller can access {@code realm} by its own credential. The realm comes from
     * the request context (realm / X-Realm), so it is checked here explicitly rather than trusted.
     */
    private void requireRealmAccess(PrincipalContext caller, String realm) {
        if (isSystemPrincipal(caller)) {
            return;
        }
        String callerUserId = authenticatedUserId(caller);
        boolean accessible = !isSystemRealm(realm)
            && credentialOf(callerUserId).map(credential -> credentialCanAccessRealm(credential, realm)).orElse(false);
        if (!accessible) {
            Log.warnf(
                "access-invite.create.refused reason=realm-not-accessible realm=%s inviter=%s",
                sanitizeForLog(realm), sanitizeForLog(callerUserId)
            );
            throw failure(Code.INVITE_REALM_NOT_ACCESSIBLE, "You cannot invite users into this realm.");
        }
    }

    /**
     * An invite is for exactly one concrete realm, the realm of the request. A body {@code realm} or
     * {@code realms} may only repeat it; several realms, a wildcard, or a pattern are refused.
     */
    private String requireSingleRealm(String contextRealm, AccessInviteRequest request) {
        String realm = contextRealm == null ? null : contextRealm.trim();
        if (realm == null || realm.isEmpty() || !CONCRETE_REALM.matcher(realm).matches()) {
            throw failure(Code.INVITE_SINGLE_REALM_REQUIRED, "An invite is for exactly one named realm.");
        }
        List<String> named = new ArrayList<>();
        if (request.getRealm() != null && !request.getRealm().isBlank()) {
            named.add(request.getRealm().trim());
        }
        if (request.getRealms() != null) {
            for (String value : request.getRealms()) {
                if (value != null && !value.isBlank()) {
                    named.add(value.trim());
                }
            }
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String value : named) {
            distinct.add(value.toLowerCase(Locale.ROOT));
        }
        if (distinct.size() > 1) {
            throw failure(Code.INVITE_SINGLE_REALM_REQUIRED, "An invite is for exactly one realm. Send one invite per realm.");
        }
        for (String value : named) {
            if (!CONCRETE_REALM.matcher(value).matches() || !value.equalsIgnoreCase(realm)) {
                throw failure(Code.INVITE_SINGLE_REALM_REQUIRED, "An invite is for exactly one realm: the realm of this request.");
            }
        }
        return realm;
    }

    private void addNormalized(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                target.add(normalized);
            }
        }
    }

    /** The system principal, or a caller that can access {@code realm} and holds {@code admin} there. */
    private boolean isRealmAdministrator(PrincipalContext caller, String realm) {
        if (isSystemPrincipal(caller)) {
            return true;
        }
        return credentialOf(authenticatedUserId(caller))
            .filter(credential -> credentialCanAccessRealm(credential, realm))
            .map(credential -> rolesInRealm(credential, realm).contains(ADMIN_ROLE))
            .orElse(false);
    }

    /** Loads an invite the caller may manage: the caller created it, or administers the realm. */
    private AccessInvite requireManageableInvite(String realm, String refName, String operation) {
        PrincipalContext caller = requireCaller();
        AccessInvite invite = inviteRepo.findByRefNameIgnoreRules(realm, refName)
            .orElseThrow(() -> failure(Code.INVITE_NOT_FOUND, "Invite not found: " + refName));
        String callerUserId = normalize(caller.getUserId());
        boolean inviter = callerUserId != null && callerUserId.equalsIgnoreCase(invite.getInvitedByUserId());
        if (!inviter && !inviteManagement(caller, realm).mayManage(invite)) {
            Log.warnf(
                "access-invite.%s.refused reason=not-inviter realm=%s invite=%s caller=%s",
                operation, realm, invite.getRefName(), sanitizeForLog(callerUserId)
            );
            throw failure(Code.INVITE_NOT_AUTHORIZED, "Only the inviter or a realm administrator can " + operation + " this invite.");
        }
        return invite;
    }

    /**
     * An inviter can grant only roles it holds in this same realm (its flat credential roles plus its
     * {@link UserRealmRole} roles for the realm). Only the framework system principal can grant
     * {@code system}, and it may grant any role.
     */
    private void enforceGrantableRoles(PrincipalContext caller, String realm, String inviterUserId, List<String> grantedRoles) {
        if (grantedRoles == null || grantedRoles.isEmpty() || isSystemPrincipal(caller)) {
            return;
        }
        Set<String> held = credentialOf(authenticatedUserId(caller))
            .map(credential -> rolesInRealm(credential, realm))
            .orElse(Set.of());
        requireRolesHeld(realm, inviterUserId, grantedRoles, held, "create");
    }

    private void requireRolesHeld(String realm, String inviterUserId, List<String> grantedRoles, Set<String> held, String operation) {
        for (String role : grantedRoles) {
            String normalized = normalize(role);
            if (normalized == null) {
                continue;
            }
            if (SYSTEM_ROLE.equals(normalized) || !held.contains(normalized)) {
                Log.warnf(
                    "access-invite.%s.refused reason=role-not-grantable realm=%s inviter=%s role=%s",
                    operation, sanitizeForLog(realm), sanitizeForLog(inviterUserId), sanitizeForLog(role)
                );
                throw failure(Code.INVITE_ROLE_NOT_GRANTABLE, "The role '" + sanitizeForLog(role) + "' cannot be granted in this realm.");
            }
        }
    }

    /**
     * Applications an invite names must be registered, and the inviter must be able to grant them in
     * this realm: the system principal or a realm administrator may grant any registered
     * application (and the admin-only {@code *}); anyone else only applications its own
     * {@link UserRealmRole} grant for the realm already includes.
     */
    private void enforceGrantableApplications(PrincipalContext caller, String realm, String inviterUserId, List<String> applications) {
        if (applications.isEmpty()) {
            return;
        }
        boolean wildcard = applications.contains(UserRealmRole.APPLICATION_WILDCARD);
        if (wildcard && applications.size() > 1) {
            throw failure(Code.INVALID_REQUEST, "The application wildcard '*' cannot be combined with named applications.");
        }
        if (!isSystemPrincipal(caller)) {
            CredentialUserIdPassword credential = credentialOf(authenticatedUserId(caller)).orElse(null);
            requireApplicationsHeld(realm, inviterUserId, credential, applications, "create");
        }
        List<String> concrete = applications.stream().filter(app -> !UserRealmRole.APPLICATION_WILDCARD.equals(app)).toList();
        if (!concrete.isEmpty()) {
            Set<String> unknown;
            try {
                unknown = applicationRegistryValidator.unknownApplications(concrete);
            }
            catch (ApplicationRegistryUnavailableException e) {
                Log.errorf(e, "access-invite.create.failed reason=application-registry-unavailable realm=%s", sanitizeForLog(realm));
                throw new WebApplicationException("The application registry is unavailable; the invite was not created.", 503);
            }
            if (unknown != null && !unknown.isEmpty()) {
                throw failure(Code.INVALID_REQUEST, "Unknown application(s): " + sanitizeForLog(String.join(", ", new java.util.TreeSet<>(unknown))));
            }
        }
    }

    private void requireApplicationsHeld(
        String realm,
        String inviterUserId,
        CredentialUserIdPassword credential,
        List<String> applications,
        String operation
    ) {
        if (applications == null || applications.isEmpty()) {
            return;
        }
        boolean admin = credential != null && rolesInRealm(credential, realm).contains(ADMIN_ROLE);
        if (admin) {
            return;
        }
        List<String> own = credential == null
            ? null
            : activeAssignment(credential.getUserId(), realm).map(UserRealmRole::getAuthorizedApplications).orElse(null);
        boolean ownWildcard = own != null && own.contains(UserRealmRole.APPLICATION_WILDCARD);
        for (String application : applications) {
            boolean held = !UserRealmRole.APPLICATION_WILDCARD.equals(application)
                && own != null
                && (ownWildcard || own.contains(application));
            if (!held) {
                Log.warnf(
                    "access-invite.%s.refused reason=application-not-grantable realm=%s inviter=%s application=%s",
                    operation, sanitizeForLog(realm), sanitizeForLog(inviterUserId), sanitizeForLog(application)
                );
                throw failure(Code.INVITE_APPLICATION_NOT_GRANTABLE, "The application '" + sanitizeForLog(application) + "' cannot be granted in this realm.");
            }
        }
    }

    /**
     * Re-checks the inviter at acceptance: it must still be able to access the realm and still hold
     * every role (and application) the invite grants there. Invites issued by the framework system
     * user are exempt; only the system principal can create those.
     */
    private void requireInviterStillEntitled(String realm, AccessInvite invite, String existingInviteeUserId) {
        String inviterUserId = normalize(invite.getInvitedByUserId());
        if (invite.isIssuedBySystemPrincipal()) {
            return;
        }
        CredentialUserIdPassword inviter = credentialOf(inviterUserId).orElse(null);
        if (inviter == null || !credentialCanAccessRealm(inviter, realm)) {
            Log.warnf(
                "access-invite.accept.refused reason=inviter-realm-not-accessible realm=%s invite=%s inviter=%s",
                sanitizeForLog(realm), invite.getRefName(), sanitizeForLog(inviterUserId)
            );
            throw failure(Code.INVITE_REALM_NOT_ACCESSIBLE, "The inviter can no longer grant access to this realm. Ask for a new invite.");
        }
        List<String> grantedRoles = invite.getGrantedRoles() == null ? List.of() : invite.getGrantedRoles();
        Set<String> held = rolesInRealm(inviter, realm);
        requireRolesHeld(realm, inviterUserId, grantedRoles, held, "accept");
        // Flat credential roles apply in every realm the user can access. Giving an existing account
        // access to this realm therefore also brings its flat roles here; the inviter must hold them
        // in this realm too, or the invite would grant roles the inviter does not have.
        CredentialUserIdPassword invitee = existingInviteeUserId == null ? null : credentialOf(existingInviteeUserId).orElse(null);
        if (invitee != null && !credentialCanAccessRealm(invitee, realm) && invitee.getRoles() != null) {
            List<String> carried = new ArrayList<>();
            for (String role : invitee.getRoles()) {
                String normalized = normalize(role);
                if (normalized != null && !BASELINE_USER_ROLE.equals(normalized)) {
                    carried.add(normalized);
                }
            }
            requireRolesHeld(realm, inviterUserId, carried, held, "accept");
        }
        requireApplicationsHeld(realm, inviterUserId, inviter, normalizeApplications(invite.getAuthorizedApplications()), "accept");
    }

    /**
     * Writes the invite's grant as the invitee's realm-scoped {@link UserRealmRole} for
     * (userId, realm): roles are unioned with any existing assignment for that realm, never written
     * to the credential's flat roles. Named applications are unioned into
     * {@code authorizedApplications}; otherwise that field is left as it is. One document per
     * (userId, realm), so accepting again merges instead of duplicating.
     */
    private UserRealmRole ensureRealmAssignment(String realm, CredentialUserIdPassword credential, InviteGrant grant) {
        String userId = credential.getUserId();
        requireUniqueAssignmentIndex();
        UserRealmRole assignment = assignment(userId, realm).orElseGet(() -> newAssignment(userId, realm, grant));
        if (assignment.getSubject() == null) {
            assignment.setSubject(credential.getSubject());
        }
        if (assignment.getStatus() == null) {
            assignment.setStatus(UserRealmRole.STATUS_ACTIVE);
        }
        assignment.setRoles(unionIgnoreCase(assignment.getRoles(), grant.getRoles()));
        if (grant.getAddTenantIds() != null && !grant.getAddTenantIds().isEmpty()) {
            // Membership in several tenants of a pooled realm: the tenant is added, never replaces
            // the others, and the assignment's default partition (its data domain) is kept.
            assignment.setAuthorizedTenantIds(unionExact(assignment.getAuthorizedTenantIds(), grant.getAddTenantIds()));
        }

        List<String> invitedApplications = normalizeApplications(grant.getAuthorizedApplications());
        if (!invitedApplications.isEmpty()) {
            List<String> current = assignment.getAuthorizedApplications();
            boolean wildcard = invitedApplications.contains(UserRealmRole.APPLICATION_WILDCARD)
                || (current != null && current.contains(UserRealmRole.APPLICATION_WILDCARD));
            assignment.setAuthorizedApplications(wildcard
                ? List.of(UserRealmRole.APPLICATION_WILDCARD)
                : unionExact(current, invitedApplications));
        }
        return realmMembershipService.upsertUserRealmRole(assignment);
    }

    /** A new assignment scoped as the acceptance policy decided (its data domain and initial tenants). */
    private UserRealmRole newAssignment(String userId, String realm, InviteGrant grant) {
        if (grant.getAssignmentDataDomain() == null) {
            throw new IllegalStateException("The acceptance policy returned no data domain for the realm assignment");
        }
        return UserRealmRole.builder()
            .refName("user-realm-role:" + userId + ":" + realm)
            .displayName(userId + " in " + realm)
            .userId(userId)
            .realmRefName(realm)
            .status(UserRealmRole.STATUS_ACTIVE)
            .authorizedTenantIds(grant.getNewAssignmentTenantIds() == null ? null : new ArrayList<>(grant.getNewAssignmentTenantIds()))
            .sponsoringOrgRefName(grant.getSponsoringOrgRefName())
            .dataDomain(grant.getAssignmentDataDomain())
            .build();
    }

    /**
     * A policy may narrow what an invite grants but never widen it: its roles and applications must
     * be ones the invite carries (and that were checked against the inviter), and in a pooled realm
     * every tenant it names must be an active member of the realm.
     */
    private void requireGrantWithinInvite(Realm realm, AccessInvite invite, InviteAcceptancePolicy policy, InviteGrant grant, boolean accountExists) {
        if (grant == null) {
            throw policyFault(realm, invite, policy, "no-grant");
        }
        Set<String> invitedRoles = new LinkedHashSet<>();
        addNormalized(invitedRoles, invite.getGrantedRoles());
        Set<String> grantRoles = new LinkedHashSet<>();
        addNormalized(grantRoles, grant.getRoles());
        if (!invitedRoles.containsAll(grantRoles)) {
            throw policyFault(realm, invite, policy, "roles-outside-invite");
        }
        List<String> invitedApplications = normalizeApplications(invite.getAuthorizedApplications());
        if (!invitedApplications.containsAll(normalizeApplications(grant.getAuthorizedApplications()))) {
            throw policyFault(realm, invite, policy, "applications-outside-invite");
        }
        if (!accountExists && grant.getHomeContext() == null) {
            throw policyFault(realm, invite, policy, "no-home-context");
        }
        if (InviteAcceptancePolicies.pooled(realm)) {
            // The invite's scope bounds the grant: every tenant and coordinate must be the scope's.
            DataDomain scope = invite.getScope() == null ? null : invite.getScope().getDataDomain();
            if (scope == null) {
                throw policyFault(realm, invite, policy, "pooled-invite-without-scope");
            }
            if (grant.getAssignmentDataDomain() != null && !sameCoordinate(scope, grant.getAssignmentDataDomain())) {
                throw policyFault(realm, invite, policy, "assignment-outside-scope");
            }
            if (!accountExists) {
                DomainContext home = grant.getHomeContext();
                if (!java.util.Objects.equals(scope.getTenantId(), home.getTenantId())
                    || !java.util.Objects.equals(scope.getOrgRefName(), home.getOrgRefName())
                    || !java.util.Objects.equals(scope.getAccountNum(), home.getAccountId())
                    || !realm.getRefName().equalsIgnoreCase(String.valueOf(home.getDefaultRealm()))) {
                    throw policyFault(realm, invite, policy, "home-context-outside-scope");
                }
            }
            if (grant.getProfileDataDomain() != null && !sameCoordinate(scope, grant.getProfileDataDomain())) {
                throw policyFault(realm, invite, policy, "profile-outside-scope");
            }
            Set<String> tenants = new LinkedHashSet<>();
            if (grant.getNewAssignmentTenantIds() != null) {
                tenants.addAll(grant.getNewAssignmentTenantIds());
            }
            if (grant.getAddTenantIds() != null) {
                tenants.addAll(grant.getAddTenantIds());
            }
            if (grant.getAssignmentDataDomain() != null) {
                tenants.add(grant.getAssignmentDataDomain().getTenantId());
            }
            if (!accountExists) {
                tenants.add(grant.getHomeContext().getTenantId());
            }
            for (String tenant : tenants) {
                if (!scope.getTenantId().equals(tenant)) {
                    throw policyFault(realm, invite, policy, "tenant-outside-scope");
                }
                if (realmMembershipService.tenantInRealm(realm.getRefName(), tenant).isEmpty()) {
                    throw policyFault(realm, invite, policy, "tenant-not-active-member");
                }
            }
        }
    }

    /** Same tenant, org, account, data segment, location and business transaction (owner may differ). */
    private static boolean sameCoordinate(DataDomain expected, DataDomain actual) {
        return java.util.Objects.equals(expected.getTenantId(), actual.getTenantId())
            && java.util.Objects.equals(expected.getOrgRefName(), actual.getOrgRefName())
            && java.util.Objects.equals(expected.getAccountNum(), actual.getAccountNum())
            && expected.getDataSegment() == actual.getDataSegment()
            && java.util.Objects.equals(expected.getLocationId(), actual.getLocationId())
            && java.util.Objects.equals(expected.getBusinessTransactionId(), actual.getBusinessTransactionId());
    }

    private AccessInviteException policyFault(Realm realm, AccessInvite invite, InviteAcceptancePolicy policy, String reason) {
        Log.errorf("access-invite.accept.refused reason=policy-grant-%s realm=%s invite=%s policy=%s",
            reason, realm.getRefName(), invite.getRefName(), policy.id());
        return failure(Code.INVITE_POLICY_UNAVAILABLE, "The invite policy for this realm returned an invalid grant.");
    }

    private static final String INVITE_FUNCTIONAL_AREA = "SECURITY";
    private static final String INVITE_FUNCTIONAL_DOMAIN = "ACCESS_INVITE";

    /**
     * The inviter's data partition, resolved by the framework's {@code DataDomainResolver} from its
     * verified principal context (principal DataDomainPolicy, then the global policy, then the
     * principal's DataDomain) for the invite's functional area. Whatever fields that yields are
     * kept. An unresolvable partition is refused; nothing falls back to default fields.
     */
    private InviteScope snapshotScope(PrincipalContext caller, String realm) {
        DataDomainResolution resolution;
        try {
            resolution = dataDomainResolver.resolveForQuery(caller, INVITE_FUNCTIONAL_AREA, INVITE_FUNCTIONAL_DOMAIN, AccessInvite.class);
        }
        catch (RuntimeException e) {
            Log.warnf(e, "access-invite.create.refused reason=partition-resolution-failed realm=%s", sanitizeForLog(realm));
            throw failure(Code.INVITE_SCOPE_UNRESOLVABLE, "Your data partition could not be resolved for this realm.");
        }
        if (resolution == null || !resolution.isResolved() || resolution.dataDomain() == null) {
            Log.warnf("access-invite.create.refused reason=partition-unresolvable realm=%s detail=%s", sanitizeForLog(realm),
                resolution == null ? "null" : sanitizeForLog(resolution.reason()));
            throw failure(Code.INVITE_SCOPE_UNRESOLVABLE, "Your data partition could not be resolved for this realm.");
        }
        DataDomain resolved = resolution.dataDomain();
        DataDomain partition = new DataDomain();
        partition.setOrgRefName(resolved.getOrgRefName());
        partition.setAccountNum(resolved.getAccountNum());
        partition.setTenantId(resolved.getTenantId());
        partition.setDataSegment(resolved.getDataSegment());
        partition.setOwnerId(resolved.getOwnerId());
        partition.setLocationId(resolved.getLocationId());
        partition.setBusinessTransactionId(resolved.getBusinessTransactionId());
        String policyFilter = null;
        java.util.Map<String, Object> facetFilters = null;
        if (resolution instanceof DataDomainResolution.Resolved detail) {
            policyFilter = detail.policyFilter();
            facetFilters = detail.facetFilters() == null || detail.facetFilters().isEmpty() ? null : new java.util.LinkedHashMap<>(detail.facetFilters());
        }
        return InviteScope.builder()
            .realmRefName(trimToNull(caller.getDefaultRealm()))
            .dataDomain(partition)
            .policyFilter(policyFilter)
            .facetFilters(facetFilters)
            .resolvedBy("DataDomainResolver:" + INVITE_FUNCTIONAL_AREA + ":" + INVITE_FUNCTIONAL_DOMAIN)
            .build();
    }

    /** A scope in the request body may only repeat the resolved one; any differing field is refused. */
    private void requireScopeClaimMatches(String realm, String callerUserId, AccessInviteScopeClaim claim, InviteScope scope) {
        if (claim == null) {
            return;
        }
        DataDomain partition = scope.getDataDomain();
        List<String> differing = new ArrayList<>();
        if (trimToNull(claim.getRealm()) != null && !claim.getRealm().trim().equalsIgnoreCase(String.valueOf(scope.getRealmRefName()))) {
            differing.add("realm");
        }
        compareClaim(differing, "tenantId", claim.getTenantId(), partition.getTenantId());
        compareClaim(differing, "orgRefName", claim.getOrgRefName(), partition.getOrgRefName());
        compareClaim(differing, "accountNum", claim.getAccountNum(), partition.getAccountNum());
        compareClaim(differing, "locationId", claim.getLocationId(), partition.getLocationId());
        compareClaim(differing, "businessTransactionId", claim.getBusinessTransactionId(), partition.getBusinessTransactionId());
        if (claim.getDataSegment() != null && claim.getDataSegment() != partition.getDataSegment()) {
            differing.add("dataSegment");
        }
        if (!differing.isEmpty()) {
            Log.warnf("access-invite.create.refused reason=scope-claim-differs realm=%s inviter=%s fields=%s",
                sanitizeForLog(realm), sanitizeForLog(callerUserId), differing);
            throw failure(Code.INVITE_SCOPE_NOT_AUTHORIZED, "An invite applies to your current data partition; the requested scope differs from it.");
        }
    }

    private static void compareClaim(List<String> differing, String field, String claimed, String resolved) {
        String value = trimToNull(claimed);
        if (value != null && !value.equals(resolved)) {
            differing.add(field);
        }
    }

    /**
     * One assignment per (userId, realm) relies on the unique index; in embedded mode it is created
     * on first use and the write fails closed if it cannot be (for example existing duplicates).
     * In remote mode the control plane owns the store and its constraints.
     */
    private void requireUniqueAssignmentIndex() {
        if (quantumModeConfig.isRemote() || uniqueAssignmentIndexReady.get()) {
            return;
        }
        userRealmRoleRepo.ensureUniqueAssignmentIndex(systemRealm());
        uniqueAssignmentIndexReady.set(true);
    }

    @FunctionalInterface
    private interface Deletion {
        long run() throws com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
    }

    /** Undoes the realm grant of a failed acceptance; failures here are logged, never swallowed silently. */
    private final class GrantRollback {
        private final String realm;
        private String userId;
        private boolean assignmentCaptured;
        private UserRealmRole priorAssignment;
        private boolean realmEntryAdded;
        private CredentialUserIdPassword createdCredential;
        /** The assignment as this acceptance wrote it (null when the write did not happen). */
        private UserRealmRole written;
        private UserProfile createdProfile;
        private UserProfile createdSystemProfile;

        private GrantRollback(String realm) {
            this.realm = realm;
        }

        private void captureAssignment(String assignmentUserId) {
            this.userId = assignmentUserId;
            this.priorAssignment = assignment(assignmentUserId, realm).orElse(null);
            this.assignmentCaptured = true;
        }

        private void run() {
            if (assignmentCaptured) {
                try {
                    restoreAssignment();
                }
                catch (RuntimeException e) {
                    Log.errorf(e, "access-invite.accept.rollback-failed step=assignment realm=%s user=%s", realm, sanitizeForLog(userId));
                }
            }
            // Profile removal cascades to the profile's credentials (UserProfileRepo.delete), so it is
            // done only for an account this acceptance created; an existing account keeps the profile.
            if (createdProfile != null && createdCredential != null) {
                try {
                    deleteOrFail(() -> userProfileRepo.delete(realm, createdProfile));
                }
                catch (RuntimeException e) {
                    Log.errorf(e, "access-invite.accept.rollback-failed step=profile realm=%s user=%s", realm, sanitizeForLog(userId));
                }
            }
            if (createdSystemProfile != null && createdCredential != null) {
                try {
                    deleteOrFail(() -> userProfileRepo.delete(systemRealm(), createdSystemProfile));
                }
                catch (RuntimeException e) {
                    Log.errorf(e, "access-invite.accept.rollback-failed step=directory-profile realm=%s user=%s", realm, sanitizeForLog(userId));
                }
            }
            try {
                if (createdCredential != null) {
                    // Already gone when the created profile's cascading delete removed it.
                    if (credentialRepo.findByUserIdIgnoreCaseIgnoreRules(createdCredential.getUserId(), systemRealm()).isPresent()) {
                        deleteOrFail(() -> credentialRepo.delete(systemRealm(), createdCredential));
                    }
                }
                else if (realmEntryAdded) {
                    credentialRepo.findByUserIdIgnoreCaseIgnoreRules(userId, systemRealm()).ifPresent(current -> {
                        List<RealmEntry> remaining = new ArrayList<>();
                        for (RealmEntry entry : current.getAuthorizedRealms()) {
                            if (entry == null || entry.getRealmRefName() == null || !realm.equalsIgnoreCase(entry.getRealmRefName().trim())) {
                                remaining.add(entry);
                            }
                        }
                        current.setAuthorizedRealms(remaining);
                        credentialRepo.save(systemRealm(), current);
                    });
                }
            }
            catch (RuntimeException e) {
                Log.errorf(e, "access-invite.accept.rollback-failed step=credential realm=%s user=%s", realm, sanitizeForLog(userId));
            }
        }

        private void deleteOrFail(Deletion deletion) {
            try {
                deletion.run();
            }
            catch (com.e2eq.framework.exceptions.ReferentialIntegrityViolationException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        /**
         * Removes only what this acceptance added, from the assignment as it is now: a change made
         * meanwhile (a suspension, a removed role, another tenant grant) is kept, never overwritten
         * with the snapshot taken before the acceptance.
         */
        private void restoreAssignment() {
            if (written == null) {
                return; // the assignment write itself failed; nothing of ours to remove
            }
            Optional<UserRealmRole> currentOpt = assignment(userId, realm);
            if (currentOpt.isEmpty()) {
                return;
            }
            UserRealmRole current = currentOpt.get();
            if (priorAssignment == null && !quantumModeConfig.isRemote()) {
                deleteOrFail(() -> userRealmRoleRepo.delete(systemRealm(), current));
                return;
            }
            UserRealmRole prior = priorAssignment;
            current.setRoles(minusIgnoreCase(current.getRoles(), added(written.getRoles(), prior == null ? null : prior.getRoles())));
            current.setAuthorizedTenantIds(minusExact(current.getAuthorizedTenantIds(),
                added(written.getAuthorizedTenantIds(), prior == null ? null : prior.getAuthorizedTenantIds())));
            List<String> priorApps = prior == null ? null : prior.getAuthorizedApplications();
            List<String> apps = minusExact(current.getAuthorizedApplications(), added(written.getAuthorizedApplications(), priorApps));
            // A wildcard grant collapses named applications into '*'; put back the ones it replaced.
            apps = unionExact(apps, added(priorApps, written.getAuthorizedApplications()));
            current.setAuthorizedApplications(priorApps == null && apps.isEmpty() ? null : apps);
            if (prior == null) {
                // Remote mode cannot delete: leave the assignment inert instead of granting anything.
                current.setStatus(UserRealmRole.STATUS_SUSPENDED);
                Log.errorf("access-invite.accept.rollback-incomplete realm=%s user=%s: remote membership store has no delete; the assignment was emptied and suspended",
                    realm, sanitizeForLog(userId));
            }
            else {
                if (!java.util.Objects.equals(prior.getStatus(), written.getStatus())
                    && java.util.Objects.equals(current.getStatus(), written.getStatus())) {
                    current.setStatus(prior.getStatus());
                }
                if (prior.getSubject() == null && written.getSubject() != null
                    && java.util.Objects.equals(current.getSubject(), written.getSubject())) {
                    current.setSubject(null);
                }
            }
            realmMembershipService.upsertUserRealmRole(current);
        }
    }

    /** Values in {@code after} that are not in {@code before} (exact match). */
    private static List<String> added(List<String> after, List<String> before) {
        List<String> out = new ArrayList<>();
        if (after == null) {
            return out;
        }
        for (String value : after) {
            if (value != null && (before == null || before.stream().noneMatch(value::equals))) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> minusExact(List<String> values, List<String> removed) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>(values);
        out.removeIf(value -> value != null && removed.contains(value));
        return out;
    }

    private static List<String> minusIgnoreCase(List<String> values, List<String> removed) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>(values);
        out.removeIf(value -> value != null && removed.stream().anyMatch(r -> r.equalsIgnoreCase(value.trim())));
        return out;
    }

    private static List<String> unionIgnoreCase(List<String> existing, List<String> added) {
        List<String> out = new ArrayList<>();
        for (List<String> source : java.util.Arrays.asList(existing, added)) {
            if (source == null) {
                continue;
            }
            for (String value : source) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String trimmed = value.trim();
                if (out.stream().noneMatch(present -> present.equalsIgnoreCase(trimmed))) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static List<String> unionExact(List<String> existing, List<String> added) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (existing != null) {
            existing.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(out::add);
        }
        if (added != null) {
            added.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(out::add);
        }
        return new ArrayList<>(out);
    }

    /** Application refNames are trimmed and de-duplicated but keep their case. */
    private static List<String> normalizeApplications(List<String> applications) {
        if (applications == null || applications.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String application : applications) {
            if (application != null && !application.isBlank()) {
                out.add(application.trim());
            }
        }
        return new ArrayList<>(out);
    }

    private String tokenForInviter(String rawToken) {
        if (returnTokenToInviter != null && returnTokenToInviter.isPresent()) {
            return returnTokenToInviter.get() ? rawToken : null;
        }
        for (AccessInviteNotificationService notificationService : notificationServices) {
            if (!(notificationService instanceof NoOpAccessInviteNotificationService)) {
                return null;
            }
        }
        return rawToken;
    }

    private String systemRealm() {
        return securityUtils.getSystemPrincipalContext().getDefaultRealm();
    }

    private <T> T inSystemScope(java.util.function.Supplier<T> action) {
        return SecurityCallScope.runWithContexts(
            securityUtils.getSystemPrincipalContext(),
            securityUtils.getSystemSecurityResourceContext(),
            action
        );
    }

    /** Strips control characters (log forging) and bounds the length of caller-supplied values. */
    private static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "_");
        return cleaned.length() > 254 ? cleaned.substring(0, 254) : cleaned;
    }

    private static AccessInviteException failure(Code code, String message) {
        return new AccessInviteException(code, message);
    }

    private String normalizeAuthenticatedUserId(String userId) {
        String normalized = normalize(userId);
        if (normalized == null) {
            return null;
        }
        String anonymousUserId = normalize(securityUtils.getEnvConfigUtils().getAnonymousUserId());
        if (anonymousUserId != null && anonymousUserId.equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private void expireInviteIfNeeded(String realm, AccessInvite invite) {
        Date now = new Date();
        if (invite.getStatus() == AccessInvite.Status.PENDING && invite.getExpiresAt() != null && !invite.getExpiresAt().after(now)) {
            // Conditional update: never overwrites a concurrent resend that rotated the token.
            inviteRepo.expirePending(realm, invite.getId(), invite.getTokenHash(), now);
            throw failure(Code.INVITE_EXPIRED, "Invite has expired.");
        }
    }

    private void ensureNoPendingDuplicate(String realm, String email, String targetUserId) {
        if (email != null) {
            boolean hasPending = inviteRepo.findByEmail(realm, email).stream().anyMatch(invite ->
                invite.getStatus() == AccessInvite.Status.PENDING
                    && invite.getExpiresAt() != null
                    && invite.getExpiresAt().after(new Date())
            );
            if (hasPending) {
                throw new WebApplicationException("A pending invite already exists for " + email, 409);
            }
        }

        if (targetUserId != null) {
            boolean hasPending = inviteRepo.findByTargetUserId(realm, targetUserId).stream().anyMatch(invite ->
                invite.getStatus() == AccessInvite.Status.PENDING
                    && invite.getExpiresAt() != null
                    && invite.getExpiresAt().after(new Date())
            );
            if (hasPending) {
                throw new WebApplicationException("A pending invite already exists for " + targetUserId, 409);
            }
        }
    }

    private List<EntityReference> toScopeReferences(List<String> scopeRefNames) {
        List<EntityReference> scopeRefs = new ArrayList<>();
        for (String scopeRefName : scopeRefNames) {
            scopeRefs.add(EntityReference.builder()
                .entityRefName(scopeRefName)
                .entityDisplayName(scopeRefName)
                .build());
        }
        return scopeRefs;
    }

    private List<String> resolveScopeRefNames(AccessInviteRequest request) {
        List<String> scopeRefNames = new ArrayList<>();
        if (request.getScopeRefNames() != null) {
            scopeRefNames.addAll(request.getScopeRefNames());
        }
        if (request.getLegalEntityRefNames() != null) {
            scopeRefNames.addAll(request.getLegalEntityRefNames());
        }
        return normalizeList(scopeRefNames);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = normalize(value);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }

    private int defaultExpiryDays(Integer expiresInDays) {
        if (expiresInDays == null || expiresInDays <= 0) {
            return 14;
        }
        return Math.min(expiresInDays, 365);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String buildDisplayName(String userId, String firstName, String lastName, String email) {
        StringBuilder joined = new StringBuilder();
        if (firstName != null && !firstName.trim().isEmpty()) {
            joined.append(firstName.trim());
        }
        if (lastName != null && !lastName.trim().isEmpty()) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(lastName.trim());
        }
        if (joined.length() > 0) {
            return joined.toString();
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return userId;
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        if (token == null || token.isBlank()) {
            throw failure(Code.INVITE_TOKEN_INVALID, "Invite token is required.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to hash invite token.", e);
        }
    }

    /** A non-secret label derived from the hash; no fragment of the raw token is ever stored. */
    private String tokenHint(String tokenHash) {
        return tokenHash.substring(0, Math.min(8, tokenHash.length()));
    }

    private static boolean constantTimeEquals(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
            stored.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8)
        );
    }

    private AccessInviteResponse toResponse(AccessInvite invite, String rawToken) {
        List<String> scopeRefNames = invite.getScopeRefs() == null
            ? List.of()
            : invite.getScopeRefs().stream().map(EntityReference::getEntityRefName).toList();
        return AccessInviteResponse.builder()
            .id(invite.getId() != null ? invite.getId().toHexString() : null)
            .refName(invite.getRefName())
            .displayName(invite.getDisplayName())
            .email(invite.getEmail())
            .targetUserId(invite.getTargetUserId())
            .scopeRefNames(scopeRefNames)
            .legalEntityRefNames(scopeRefNames)
            .grantedRoles(invite.getGrantedRoles() == null ? List.of() : invite.getGrantedRoles())
            .allowedFunctionalAreas(invite.getAllowedFunctionalAreas() == null ? List.of() : invite.getAllowedFunctionalAreas())
            .allowedFunctionalDomains(invite.getAllowedFunctionalDomains() == null ? List.of() : invite.getAllowedFunctionalDomains())
            .allowedActions(invite.getAllowedActions() == null ? List.of() : invite.getAllowedActions())
            .authorizedApplications(invite.getAuthorizedApplications() == null ? List.of() : invite.getAuthorizedApplications())
            .acceptancePolicy(invite.getAcceptancePolicy())
            .scope(invite.getScope())
            .status(invite.getStatus() != null ? invite.getStatus().name() : null)
            .expiresAt(invite.getExpiresAt())
            .acceptedAt(invite.getAcceptedAt())
            .acceptedUserId(invite.getAcceptedUserId())
            .inviteToken(rawToken)
            .build();
    }
}
