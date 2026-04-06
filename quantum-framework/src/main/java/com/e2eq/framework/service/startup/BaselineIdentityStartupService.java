package com.e2eq.framework.service.startup;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.MongoWriteException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Date;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class BaselineIdentityStartupService {

    private static final String TENANT_ADMIN_GROUP_REF = "tenant-admin-users";
    private static final String TENANT_ADMIN_GROUP_DISPLAY = "Tenant Admin Users";

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserGroupRepo userGroupRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @ConfigProperty(name = "quantum.defaultSystemPassword", defaultValue = "test123456")
    String defaultSystemPassword;

    @ConfigProperty(name = "quantum.defaultTestPassword", defaultValue = "test123!")
    String defaultDemoPassword;

    public void onStart() {
        ensureSystemUser();
        ensureTenantAdmin(
                "admin@" + envConfigUtils.getSystemTenantId(),
                defaultSystemPassword,
                DomainContext.builder()
                        .tenantId(envConfigUtils.getSystemTenantId())
                        .orgRefName(envConfigUtils.getSystemOrgRefName())
                        .defaultRealm(envConfigUtils.getSystemRealm())
                        .accountId(envConfigUtils.getSystemAccountNumber())
                        .build()
        );
        ensureTenantAdmin(
                "admin@" + envConfigUtils.getDefaultTenantId(),
                defaultDemoPassword,
                DomainContext.builder()
                        .tenantId(envConfigUtils.getDefaultTenantId())
                        .orgRefName(envConfigUtils.getDefaultOrgRefName())
                        .defaultRealm(envConfigUtils.getDefaultRealm())
                        .accountId(envConfigUtils.getDefaultAccountNumber())
                        .build()
        );
        ensureTenantAdmin(
                "admin@" + envConfigUtils.getTestTenantId(),
                defaultDemoPassword,
                DomainContext.builder()
                        .tenantId(envConfigUtils.getTestTenantId())
                        .orgRefName(envConfigUtils.getTestOrgRefName())
                        .defaultRealm(envConfigUtils.getTestRealm())
                        .accountId(envConfigUtils.getTestAccountNumber())
                        .build()
        );
    }

    private void ensureSystemUser() {
        ensureCredential(
                envConfigUtils.getSystemUserId(),
                defaultSystemPassword,
                Set.of("system", "admin", "user"),
                DomainContext.builder()
                        .tenantId(envConfigUtils.getSystemTenantId())
                        .orgRefName(envConfigUtils.getSystemOrgRefName())
                        .defaultRealm(envConfigUtils.getSystemRealm())
                        .accountId(envConfigUtils.getSystemAccountNumber())
                        .build(),
                true
        );
    }

    private void ensureTenantAdmin(String userId, String password, DomainContext domainContext) {
        ensureCredential(userId, password, Set.of("admin", "user"), domainContext, false);
        ensureTenantAdminMembership(userId, domainContext);
    }

    private void ensureCredential(
            String userId,
            String password,
            Set<String> roles,
            DomainContext domainContext,
            boolean allowAllRealms
    ) {
        PrincipalContext principal = buildStartupPrincipal(userId, domainContext);
        ResourceContext resource = SecurityCallScope.resource(
                principal,
                envConfigUtils.getSystemRealm(),
                "security",
                "credentialUserIdPassword",
                "UPDATE"
        );

        SecurityCallScope.runWithContexts(principal, resource, () -> {
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
                Optional<CredentialUserIdPassword> credentialOpt = credentialRepo.findByUserId(
                        userId,
                        envConfigUtils.getSystemRealm(),
                        true
                );

                if (credentialOpt.isEmpty()) {
                    Log.infof("BaselineIdentityStartupService: creating missing startup user %s", userId);
                    credentialOpt = Optional.of(createCredential(userId, password, roles, domainContext, allowAllRealms));
                    credentialOpt = credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true);
                }

                credentialOpt.ifPresent(credential -> {
                    reconcileCredential(credential, password, roles, domainContext, allowAllRealms);
                    ensureUserProfile(userId, credential, domainContext);
                });
            }
        });
    }

    private void reconcileCredential(CredentialUserIdPassword credential,
                                     String password,
                                     Set<String> roles,
                                     DomainContext expectedDomainContext,
                                     boolean allowAllRealms) {
        boolean updated = false;

        if (!Objects.equals(credential.getRefName(), credential.getUserId())) {
            credential.setRefName(credential.getUserId());
            updated = true;
        }

        if (!Objects.equals(credential.getDisplayName(), credential.getUserId())) {
            credential.setDisplayName(credential.getUserId());
            updated = true;
        }

        if (password != null && !password.isBlank() && !EncryptionUtils.checkPassword(password, credential.getPasswordHash())) {
            credential.setPasswordHash(EncryptionUtils.hashPassword(password));
            updated = true;
        }

        if (expectedDomainContext != null && !expectedDomainContext.equals(credential.getDomainContext())) {
            credential.setDomainContext(expectedDomainContext);
            updated = true;
        }

        String[] expectedRoles = roles == null ? new String[0] : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .sorted()
                .toArray(String[]::new);
        String[] currentRoles = credential.getRoles() == null ? new String[0] : java.util.Arrays.stream(credential.getRoles())
                .filter(role -> role != null && !role.isBlank())
                .sorted()
                .toArray(String[]::new);
        if (!java.util.Arrays.equals(currentRoles, expectedRoles)) {
            credential.setRoles(expectedRoles);
            updated = true;
        }

        DataDomain expectedDataDomain = buildDataDomain(expectedDomainContext, credential.getUserId());
        if (!expectedDataDomain.equals(credential.getDataDomain())) {
            credential.setDataDomain(expectedDataDomain);
            updated = true;
        }

        String authProviderName = authProviderFactory.getAuthProvider().getName();
        if (authProviderName != null && !authProviderName.equals(credential.getAuthProviderName())) {
            credential.setAuthProviderName(authProviderName);
            updated = true;
        }

        if (allowAllRealms && !"*".equals(credential.getRealmRegEx())) {
            credential.setRealmRegEx("*");
            updated = true;
        }

        if (updated) {
            credential.setLastUpdate(new Date());
            credentialRepo.save(envConfigUtils.getSystemRealm(), credential);
            Log.infof("BaselineIdentityStartupService: refreshed startup credential for %s", credential.getUserId());
        }
    }

    private CredentialUserIdPassword createCredential(
            String userId,
            String password,
            Set<String> roles,
            DomainContext domainContext,
            boolean allowAllRealms
    ) {
        DataDomain dataDomain = buildDataDomain(domainContext, userId);

        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setRefName(userId);
        credential.setUserId(userId);
        credential.setSubject(UUID.randomUUID().toString());
        credential.setForceChangePassword(Boolean.FALSE);
        credential.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
        credential.setPasswordHash(EncryptionUtils.hashPassword(password));
        credential.setDomainContext(domainContext);
        credential.setRoles(roles.toArray(new String[0]));
        credential.setLastUpdate(new Date());
        credential.setAuthProviderName(authProviderFactory.getAuthProvider().getName());
        credential.setDataDomain(dataDomain);
        if (allowAllRealms) {
            credential.setRealmRegEx("*");
        }
        credentialRepo.save(envConfigUtils.getSystemRealm(), credential);
        Log.infof("BaselineIdentityStartupService: created startup credential for %s", userId);
        return credential;
    }

    private void ensureUserProfile(String userId, CredentialUserIdPassword credential, DomainContext domainContext) {
        String profileRealm = domainContext.getDefaultRealm() == null || domainContext.getDefaultRealm().isBlank()
                ? envConfigUtils.getSystemRealm()
                : domainContext.getDefaultRealm();
        EntityReference credentialRef = credential.createEntityReference(envConfigUtils.getSystemRealm());

        Optional<UserProfile> profileOpt = userProfileRepo.getByUserIdWithIgnoreRules(profileRealm, userId);
        UserProfile profile = profileOpt.orElseGet(UserProfile::new);
        boolean updated = reconcileUserProfile(profile, userId, credentialRef, domainContext, profileOpt.isEmpty());

        if (updated) {
            try {
                userProfileRepo.persistStartupProfile(profileRealm, profile);
                Log.infof("BaselineIdentityStartupService: upserted startup user profile for %s in realm %s", userId, profileRealm);
            } catch (MongoWriteException mongoWriteException) {
                if (!isDuplicateKey(mongoWriteException)) {
                    throw mongoWriteException;
                }
                Optional<UserProfile> existing = userProfileRepo.getByUserIdWithIgnoreRules(profileRealm, userId);
                if (existing.isEmpty()) {
                    throw mongoWriteException;
                }
                UserProfile reloaded = existing.get();
                if (reconcileUserProfile(reloaded, userId, credentialRef, domainContext, false)) {
                    userProfileRepo.persistStartupProfile(profileRealm, reloaded);
                }
                Log.infof("BaselineIdentityStartupService: reused existing startup user profile for %s in realm %s after duplicate insert attempt", userId, profileRealm);
            }
        }
    }

    private boolean reconcileUserProfile(
            UserProfile profile,
            String userId,
            EntityReference credentialRef,
            DomainContext domainContext,
            boolean created
    ) {
        boolean updated = created;
        if (profile.getRefName() == null || profile.getRefName().isBlank()) {
            profile.setRefName(userId);
            updated = true;
        }
        if (profile.getUserId() == null || profile.getUserId().isBlank()) {
            profile.setUserId(userId);
            updated = true;
        }
        if (profile.getEmail() == null || profile.getEmail().isBlank()) {
            profile.setEmail(userId);
            updated = true;
        }
        if (!Objects.equals(profile.getCredentialUserIdPasswordRef(), credentialRef)) {
            profile.setCredentialUserIdPasswordRef(credentialRef);
            updated = true;
        }

        DataDomain expectedDataDomain = buildDataDomain(domainContext, userId);
        if (!Objects.equals(profile.getDataDomain(), expectedDataDomain)) {
            profile.setDataDomain(expectedDataDomain);
            updated = true;
        }
        return updated;
    }

    private boolean isDuplicateKey(MongoWriteException exception) {
        return exception != null
                && exception.getError() != null
                && exception.getError().getCode() == 11000;
    }

    private void ensureTenantAdminMembership(String userId, DomainContext domainContext) {
        String realm = domainContext == null ? null : domainContext.getDefaultRealm();
        if (realm == null || realm.isBlank() || userId == null || userId.isBlank()) {
            return;
        }

        PrincipalContext principal = buildStartupPrincipal(userId, domainContext);
        ResourceContext resource = SecurityCallScope.resource(
                principal,
                realm,
                "security",
                "userGroup",
                "UPDATE"
        );

        SecurityCallScope.runWithContexts(principal, resource, () -> {
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
                Optional<UserGroup> groupOpt = userGroupRepo.findByRefName(TENANT_ADMIN_GROUP_REF, realm);
                UserGroup group = groupOpt.orElseGet(UserGroup::new);
                boolean updated = groupOpt.isEmpty();
                if (group.getRefName() == null || group.getRefName().isBlank()) {
                    group.setRefName(TENANT_ADMIN_GROUP_REF);
                    updated = true;
                }
                if (group.getDisplayName() == null || group.getDisplayName().isBlank()) {
                    group.setDisplayName(TENANT_ADMIN_GROUP_DISPLAY);
                    updated = true;
                }
                if (group.getDataDomain() == null) {
                    group.setDataDomain(buildDataDomain(domainContext, userId));
                    updated = true;
                }
                java.util.List<String> roles = group.getRoles() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(group.getRoles());
                if (roles.stream().noneMatch(role -> "admin".equalsIgnoreCase(role))) {
                    roles.add("admin");
                    group.setRoles(roles);
                    updated = true;
                }
                java.util.List<EntityReference> members = group.getUserProfiles() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(group.getUserProfiles());

                boolean exists = members.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(ref -> userId.equalsIgnoreCase(ref.getEntityRefName()));
                if (exists) {
                    if (updated) {
                        userGroupRepo.save(realm, group);
                    }
                    return;
                }

                members.add(EntityReference.builder()
                        .entityRefName(userId)
                        .entityDisplayName(userId)
                        .entityType(UserProfile.class.getSimpleName())
                        .build());
                group.setUserProfiles(members);
                userGroupRepo.save(realm, group);
            }
        });
    }

    private PrincipalContext buildStartupPrincipal(String userId, DomainContext domainContext) {
        return SecurityCallScope.service(
                envConfigUtils.getSystemRealm(),
                buildDataDomain(domainContext, userId),
                envConfigUtils.getSystemUserId(),
                "SYSTEM",
                "admin",
                "user"
        );
    }

    private DataDomain buildDataDomain(DomainContext domainContext, String ownerId) {
        return DataDomain.builder()
                .orgRefName(domainContext.getOrgRefName())
                .accountNum(domainContext.getAccountId())
                .tenantId(domainContext.getTenantId())
                .ownerId(ownerId)
                .build();
    }
}
