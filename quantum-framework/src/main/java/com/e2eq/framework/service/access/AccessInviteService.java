package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.AccessInviteRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.CredentialUserIdPassword.RealmEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

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

@ApplicationScoped
public class AccessInviteService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Inject
    AccessInviteRepo inviteRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    Instance<AccessInviteProvisioner> provisioners;

    @Inject
    Instance<AccessInviteNotificationService> notificationServices;

    public AccessInviteResponse createInvite(String realm, String inviterUserId, AccessInviteRequest request) {
        String normalizedEmail = normalize(request.getEmail());
        String normalizedTargetUserId = normalize(request.getTargetUserId());
        if (normalizedEmail == null && normalizedTargetUserId == null) {
            throw new BadRequestException("Invite email or target user ID is required.");
        }

        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.validateInviteCreation(realm, inviterUserId, request);
        }

        List<String> scopeRefNames = resolveScopeRefNames(request);
        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.validateScopes(realm, inviterUserId, scopeRefNames);
        }

        ensureNoPendingDuplicate(realm, normalizedEmail, normalizedTargetUserId);

        String rawToken = generateToken();
        AccessInvite invite = AccessInvite.builder()
            .refName("invite-" + Instant.now().toEpochMilli())
            .displayName("Invite " + (normalizedEmail != null ? normalizedEmail : normalizedTargetUserId))
            .email(normalizedEmail)
            .targetUserId(normalizedTargetUserId)
            .invitedByUserId(normalize(inviterUserId))
            .tokenHash(sha256(rawToken))
            .tokenHint(rawToken.substring(Math.max(0, rawToken.length() - 8)))
            .scopeRefs(toScopeReferences(scopeRefNames))
            .grantedRoles(normalizeList(request.getGrantedRoles()))
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
        return toResponse(invite, rawToken);
    }

    public List<AccessInviteResponse> listInvites(String realm) {
        return inviteRepo.getAllListIgnoreRules(realm).stream()
            .map(invite -> toResponse(invite, null))
            .toList();
    }

    public AccessInviteResponse getInvite(String realm, String refName) {
        AccessInvite invite = inviteRepo.findByRefNameIgnoreRules(realm, refName)
            .orElseThrow(() -> new NotFoundException("Invite not found: " + refName));
        return toResponse(invite, null);
    }

    public AccessInviteResponse revokeInvite(String realm, String refName) {
        AccessInvite invite = inviteRepo.findByRefNameIgnoreRules(realm, refName)
            .orElseThrow(() -> new NotFoundException("Invite not found: " + refName));
        invite.setStatus(AccessInvite.Status.REVOKED);
        invite = inviteRepo.save(realm, invite);
        return toResponse(invite, null);
    }

    public AccessInviteAcceptResponse acceptInvite(AccessInviteAcceptRequest request) {
        String realm = normalize(request.getRealm());
        if (realm == null) {
            realm = SecurityContext.getPrincipalContext().map(pctx -> normalize(pctx.getDefaultRealm())).orElse(null);
        }
        if (realm == null) {
            throw new BadRequestException("Invite acceptance requires the target realm.");
        }

        String tokenHash = sha256(request.getToken());
        AccessInvite invite = inviteRepo.findByTokenHash(realm, tokenHash)
            .orElseThrow(() -> new NotFoundException("Invite token is invalid."));
        expireInviteIfNeeded(realm, invite);

        if (invite.getStatus() != AccessInvite.Status.PENDING) {
            throw new BadRequestException("Invite is no longer pending.");
        }

        String authenticatedUserId = SecurityContext.getPrincipalContext()
            .map(pctx -> normalizeAuthenticatedUserId(pctx.getUserId()))
            .orElse(null);
        String effectiveUserId = determineEffectiveUserId(invite, request, authenticatedUserId);
        if (authenticatedUserId != null && !authenticatedUserId.equalsIgnoreCase(effectiveUserId)) {
            throw new ForbiddenException("Authenticated user does not match this invite.");
        }

        final String finalRealm = realm;
        final String finalEffectiveUserId = effectiveUserId;
        String effectiveEmail = normalize(request.getEmail() != null ? request.getEmail() : invite.getEmail());
        if (effectiveEmail == null) {
            effectiveEmail = effectiveUserId;
        }
        final String finalEffectiveEmail = effectiveEmail;
        final String firstName = request.getFirstName();
        final String lastName = request.getLastName();
        final String password = request.getPassword();

        return SecurityCallScope.runWithContexts(
            securityUtils.getSystemPrincipalContext(),
            securityUtils.getSystemSecurityResourceContext(),
            () -> acceptInviteInSystemScope(
                finalRealm,
                invite,
                finalEffectiveUserId,
                finalEffectiveEmail,
                firstName,
                lastName,
                password
            )
        );
    }

    private AccessInviteAcceptResponse acceptInviteInSystemScope(
        String realm,
        AccessInvite invite,
        String effectiveUserId,
        String effectiveEmail,
        String firstName,
        String lastName,
        String password
    ) {
        CredentialUserIdPassword credential = ensureUserCredential(
            realm,
            effectiveUserId,
            password,
            invite.getGrantedRoles()
        );
        UserProfile profile = ensureUserProfile(realm, credential, effectiveUserId, effectiveEmail, firstName, lastName);
        for (AccessInviteProvisioner provisioner : provisioners) {
            provisioner.onInviteAccepted(realm, invite, credential, profile);
        }

        invite.setAcceptedAt(new Date());
        invite.setAcceptedUserId(profile.getUserId() != null ? profile.getUserId() : effectiveUserId);
        invite.setStatus(AccessInvite.Status.ACCEPTED);
        inviteRepo.save(realm, invite);

        return AccessInviteAcceptResponse.builder()
            .userId(profile.getUserId() != null ? profile.getUserId() : effectiveUserId)
            .email(effectiveEmail)
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
        String password,
        List<String> grantedRoles
    ) {
        Optional<CredentialUserIdPassword> existing = credentialRepo.findByUserId(userId, securityUtils.getSystemPrincipalContext().getDefaultRealm(), true);
        if (existing.isPresent()) {
            CredentialUserIdPassword credential = existing.get();
            ensureRealmAuthorization(credential, realm);
            return credentialRepo.save(securityUtils.getSystemPrincipalContext().getDefaultRealm(), credential);
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
        authProviderFactory.getUserManager().createUser(
            userId,
            password,
            false,
            roles,
            domainContext
        );

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

    private String determineEffectiveUserId(AccessInvite invite, AccessInviteAcceptRequest request, String authenticatedUserId) {
        if (authenticatedUserId != null) {
            return authenticatedUserId;
        }
        String requestedUserId = normalize(request.getUserId());
        if (requestedUserId != null) {
            return requestedUserId;
        }
        String targetUserId = normalize(invite.getTargetUserId());
        if (targetUserId != null) {
            return targetUserId;
        }
        String email = normalize(request.getEmail() != null ? request.getEmail() : invite.getEmail());
        if (email != null) {
            return email;
        }
        throw new BadRequestException("Invite acceptance could not determine a target user ID.");
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
        if (invite.getExpiresAt() != null && invite.getExpiresAt().before(new Date()) && invite.getStatus() == AccessInvite.Status.PENDING) {
            invite.setStatus(AccessInvite.Status.EXPIRED);
            inviteRepo.save(realm, invite);
            throw new BadRequestException("Invite has expired.");
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

    private String sha256(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Invite token is required.");
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
