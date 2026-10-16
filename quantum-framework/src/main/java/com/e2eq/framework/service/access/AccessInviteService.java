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
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.service.access.AccessInviteException.Code;
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

@ApplicationScoped
public class AccessInviteService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String ADMIN_ROLE = "admin";
    private static final String SYSTEM_ROLE = "system";

    @Inject
    AccessInviteRepo inviteRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserRealmRoleRepo userRealmRoleRepo;

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

    public AccessInviteResponse createInvite(String realm, String inviterUserId, AccessInviteRequest request) {
        if (request == null) {
            throw failure(Code.INVALID_REQUEST, "Invite request body is required.");
        }
        String normalizedEmail = normalize(request.getEmail());
        String normalizedTargetUserId = normalize(request.getTargetUserId());
        if (normalizedEmail == null && normalizedTargetUserId == null) {
            throw new BadRequestException("Invite email or target user ID is required.");
        }

        List<String> grantedRoles = normalizeList(request.getGrantedRoles());
        enforceGrantableRoles(realm, inviterUserId, grantedRoles);

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
            .allowedFunctionalAreas(normalizeList(request.getAllowedFunctionalAreas()))
            .allowedFunctionalDomains(normalizeList(request.getAllowedFunctionalDomains()))
            .allowedActions(normalizeList(request.getAllowedActions()))
            .inviteMessage(request.getInviteMessage())
            .expiresAt(Date.from(Instant.now().plus(defaultExpiryDays(request.getExpiresInDays()), ChronoUnit.DAYS)))
            .status(AccessInvite.Status.PENDING)
            .dataDomain(buildDataDomain(inviterUserId))
            .build();

        invite = inviteRepo.save(realm, invite);
        for (AccessInviteNotificationService notificationService : notificationServices) {
            notificationService.sendInviteNotification(realm, invite, rawToken);
        }
        return toResponse(invite, tokenForInviter(rawToken));
    }

    /**
     * Lists invites in the realm. Administrators see every invite; any other caller sees only the
     * invites it created. Tokens are never included.
     */
    public List<AccessInviteResponse> listInvites(String realm) {
        PrincipalContext caller = requireCaller();
        boolean admin = isRealmAdministrator(caller, realm);
        String callerUserId = normalize(caller.getUserId());
        return inviteRepo.getAllListIgnoreRules(realm).stream()
            .filter(invite -> admin || (callerUserId != null && callerUserId.equalsIgnoreCase(invite.getInvitedByUserId())))
            .map(invite -> toResponse(invite, null))
            .toList();
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
        String realm = normalize(request.getRealm());
        if (realm == null) {
            realm = principal.map(pctx -> normalize(pctx.getDefaultRealm())).orElse(null);
        }
        if (realm == null) {
            throw failure(Code.INVALID_REQUEST, "Invite acceptance requires the target realm.");
        }

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
                    finalRealm,
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
        String realm,
        AccessInvite invite,
        String invitedUserId,
        String invitedEmail,
        boolean accountExists,
        String firstName,
        String lastName,
        String password
    ) {
        CredentialUserIdPassword credential = ensureUserCredential(
            realm,
            invitedUserId,
            accountExists,
            password,
            invite.getGrantedRoles()
        );
        UserProfile profile = ensureUserProfile(realm, credential, invitedUserId, invitedEmail, firstName, lastName);
        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.onInviteAccepted(realm, invite, credential, profile);
        }

        return AccessInviteAcceptResponse.builder()
            .userId(invitedUserId)
            .email(invitedEmail)
            .defaultRealm(realm)
            .inviteRefName(invite.getRefName())
            .grantedScopes(invite.getScopeRefs() == null ? List.of() : invite.getScopeRefs().stream().map(EntityReference::getEntityRefName).toList())
            .build();
    }

    private UserProfile ensureUserProfile(
        String realm,
        CredentialUserIdPassword credential,
        String userId,
        String email,
        String firstName,
        String lastName
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
            .dataDomain(buildDataDomain(userId))
            .build();
        return userProfileRepo.save(realm, profile);
    }

    private CredentialUserIdPassword ensureUserCredential(
        String realm,
        String userId,
        boolean accountExists,
        String password,
        List<String> grantedRoles
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

        Set<String> roles = new LinkedHashSet<>();
        if (grantedRoles != null) {
            roles.addAll(grantedRoles);
        }
        if (roles.isEmpty()) {
            roles.add("user");
        }

        DomainContext domainContext = buildInviteDomainContext(realm);
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

    private DomainContext buildInviteDomainContext(String realm) {
        DataDomain base = buildDataDomain("invite");
        return DomainContext.builder()
            .tenantId(realm)
            .defaultRealm(realm)
            .orgRefName(base.getOrgRefName())
            .accountId(base.getAccountNum())
            .dataSegment(base.getDataSegment())
            .build();
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
     * Roles the caller holds in {@code realm} specifically: its credential roles only when that
     * realm is the credential's home realm, plus its active realm-role assignment for the realm.
     * The merged principal role list is deliberately not used, because it carries home-realm
     * roles into any realm the caller switches to.
     */
    private Set<String> rolesInRealm(PrincipalContext caller, String realm) {
        String callerUserId = authenticatedUserId(caller);
        Set<String> roles = new LinkedHashSet<>();
        if (callerUserId == null || realm == null) {
            return roles;
        }
        inSystemScope(() -> {
            credentialRepo.findByUserIdIgnoreCaseIgnoreRules(callerUserId, systemRealm()).ifPresent(credential -> {
                String homeRealm = credential.getDomainContext() != null ? credential.getDomainContext().getDefaultRealm() : null;
                if (homeRealm != null && homeRealm.equalsIgnoreCase(realm) && credential.getRoles() != null) {
                    addNormalized(roles, List.of(credential.getRoles()));
                }
                userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(credential.getUserId(), realm, systemRealm())
                    .ifPresent(assignment -> addNormalized(roles, assignment.getRoles()));
            });
            return null;
        });
        return roles;
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

    private boolean isRealmAdministrator(PrincipalContext caller, String realm) {
        return isSystemPrincipal(caller) || rolesInRealm(caller, realm).contains(ADMIN_ROLE);
    }

    /** Loads an invite the caller may manage: the caller created it, or administers the realm. */
    private AccessInvite requireManageableInvite(String realm, String refName, String operation) {
        PrincipalContext caller = requireCaller();
        AccessInvite invite = inviteRepo.findByRefNameIgnoreRules(realm, refName)
            .orElseThrow(() -> failure(Code.INVITE_NOT_FOUND, "Invite not found: " + refName));
        String callerUserId = normalize(caller.getUserId());
        boolean inviter = callerUserId != null && callerUserId.equalsIgnoreCase(invite.getInvitedByUserId());
        if (!inviter && !isRealmAdministrator(caller, realm)) {
            Log.warnf(
                "access-invite.%s.refused reason=not-inviter realm=%s invite=%s caller=%s",
                operation, realm, invite.getRefName(), sanitizeForLog(callerUserId)
            );
            throw failure(Code.INVITE_NOT_AUTHORIZED, "Only the inviter or a realm administrator can " + operation + " this invite.");
        }
        return invite;
    }

    /**
     * An inviter cannot hand out a role it does not hold itself. Administrators and the system
     * principal may grant any role.
     */
    private void enforceGrantableRoles(String realm, String inviterUserId, List<String> grantedRoles) {
        if (grantedRoles == null || grantedRoles.isEmpty()) {
            return;
        }
        PrincipalContext caller = requireCaller();
        if (isSystemPrincipal(caller)) {
            return;
        }
        Set<String> held = rolesInRealm(caller, realm);
        for (String role : grantedRoles) {
            if (SYSTEM_ROLE.equals(role) || !held.contains(role)) {
                Log.warnf(
                    "access-invite.create.refused reason=role-not-grantable realm=%s inviter=%s role=%s",
                    realm, sanitizeForLog(inviterUserId), sanitizeForLog(role)
                );
                throw failure(Code.INVITE_ROLE_NOT_GRANTABLE, "You cannot grant the role '" + role + "' in this realm.");
            }
        }
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
            .status(invite.getStatus() != null ? invite.getStatus().name() : null)
            .expiresAt(invite.getExpiresAt())
            .acceptedAt(invite.getAcceptedAt())
            .acceptedUserId(invite.getAcceptedUserId())
            .inviteToken(rawToken)
            .build();
    }
}
