package com.e2eq.framework.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.RealmSetupStatus;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.service.seed.*;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.Data;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.util.*;

/**
 * Service to provision a new tenant: creates a Realm, runs migrations on that realm DB,
 * and seeds an initial admin credential in the newly created realm.
 */
@ApplicationScoped
public class TenantProvisioningService {
    private static final int DROP_PENDING_MAX_RETRIES = 12;
    private static final int DROP_PENDING_RETRY_DELAY_MS = 500;
    private static final String DEFAULT_DEMO_PASSWORD = "demo123!";
    private static final String TENANT_ADMIN_GROUP_REF = "tenant-admin-users";
    private static final String TENANT_ADMIN_GROUP_DISPLAY = "Tenant Admin Users";

    @Inject RealmRepo realmRepo;
    @Inject MigrationService migrationService;
    @Inject AuthProviderFactory authProviderFactory;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject CredentialRepo credentialRepo;
    @Inject RealmTenantMembershipRepo realmTenantMembershipRepo;
    @Inject UserProfileRepo userProfileRepo;
    @Inject UserGroupRepo userGroupRepo;
    @Inject MongoClient mongoClient;

    @Inject SeedLoaderService seedLoaderService;

    @Inject SeedDiscoveryService seedDiscoveryService;

    public static class ProvisionResult {
        public String realmId;
        public boolean realmCreated;
        public boolean userCreated;
        public List<String> appliedSeedArchetypes = new java.util.ArrayList<>();
        public List<String> warnings = new java.util.ArrayList<>();
        public void addWarning(String w) { if (w != null && !w.isBlank()) warnings.add(w); }
    }

    public static class DeleteResult {
        public String realmId;
        public boolean realmCatalogDeleted;
        public boolean databaseDropped;
        public int deletedCredentialCount;
        public List<String> warnings = new java.util.ArrayList<>();
        public void addWarning(String w) { if (w != null && !w.isBlank()) warnings.add(w); }
    }

    @Data
    @Builder
    public static class ProvisionTenantCommand {
        private String tenantDisplayName;
        private String tenantEmailDomain;
        private String orgRefName;
        private String accountId;
        private String adminUserId;
        private String adminSubject;
        private String adminPassword;
        @Builder.Default
        private List<String> archetypes = List.of();
        @Builder.Default
        private boolean overwriteAll = true;
    }

    @Data
    @Builder
    public static class ProvisioningContext {
        private ProvisionTenantCommand command;
        private ProvisionResult result;
        private String systemRealm;
        private String realmId;
        private String normalizedTenantDisplayName;
        private DomainContext domainContext;
        private DataDomain dataDomain;
        private Realm desiredRealm;
        private PrincipalContext tenantPrincipal;
        private ResourceContext migrationResource;
        private ResourceContext seedResource;
        private MultiEmitter<String> emitter;
        private Set<String> desiredRoles;
        private String baselineAdminUserId;
        private String demoUserId;
        private String demoPassword;
        private List<String> archetypes;
    }

    /**
     * Provisions a new tenant.
     *
     * @param tenantEmailDomain e.g. "acme.com"
     * @param orgRefName        e.g. "acme.com"
     * @param accountId         e.g. initial account number for the tenant
     * @param adminUserId       e.g. "admin@acme.com"
     * @param adminSubject      the unique immutable identifier for this user
     * @param adminPassword     plaintext password to be hashed and stored
     * @return details about what happened and any warnings
     */
    public ProvisionResult provisionTenant(String tenantEmailDomain,
                                           String orgRefName,
                                           String accountId,
                                           String adminUserId,
                                           String adminSubject,
                                           String adminPassword) {
        return provisionTenant(null, tenantEmailDomain, orgRefName, accountId, adminUserId, adminSubject, adminPassword, java.util.List.of(), true);
    }

    /**
     * Provisions a new tenant and applies the specified seed archetypes.
     * @param archetypes list of archetype names to apply after migrations
     */
    public ProvisionResult provisionTenant(String tenantDisplayName,
                                           String tenantEmailDomain,
                                           String orgRefName,
                                           String accountId,
                                           String adminUserId,
                                           String adminSubject,
                                           String adminPassword,
                                           List<String> archetypes,
                                           boolean overwriteAll) {
        return provisionTenant(ProvisionTenantCommand.builder()
            .tenantDisplayName(tenantDisplayName)
            .tenantEmailDomain(tenantEmailDomain)
            .orgRefName(orgRefName)
            .accountId(accountId)
            .adminUserId(adminUserId)
            .adminSubject(adminSubject)
            .adminPassword(adminPassword)
            .archetypes(archetypes == null ? List.of() : archetypes)
            .overwriteAll(overwriteAll)
            .build());
    }

    public ProvisionResult provisionTenant(ProvisionTenantCommand command) {
        ProvisioningContext context = initializeContext(command);
        ensureRealmCatalog(context);
        ensureRealmMembership(context);
        runRealmMigrations(context);
        ensureTenantIdentities(context);
        applyBaseSeedPacks(context);
        applyRequestedArchetypes(context);
        verifyRealmInitialization(context);
        return context.getResult();
    }

    public ProvisioningContext initializeContext(ProvisionTenantCommand command) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(command.getTenantEmailDomain(), "tenantEmailDomain cannot be null");
        Objects.requireNonNull(command.getOrgRefName(), "orgRefName cannot be null");
        Objects.requireNonNull(command.getAccountId(), "accountId cannot be null");
        Objects.requireNonNull(command.getAdminUserId(), "adminUserId cannot be null");
        Objects.requireNonNull(command.getAdminSubject(), "adminSubject cannot be null");

        String realmId = command.getTenantEmailDomain().replace('.', '-');
        String normalizedTenantDisplayName = command.getTenantDisplayName() == null || command.getTenantDisplayName().isBlank()
            ? realmId
            : command.getTenantDisplayName().trim();

        ProvisionResult result = new ProvisionResult();
        result.realmId = realmId;

        DomainContext dc = DomainContext.builder()
            .tenantId(command.getTenantEmailDomain())
            .orgRefName(command.getOrgRefName())
            .accountId(command.getAccountId())
            .defaultRealm(realmId)
            .build();

        DataDomain dataDomain = DataDomain.builder()
            .orgRefName(dc.getOrgRefName())
            .accountNum(dc.getAccountId())
            .tenantId(dc.getTenantId())
            .ownerId(command.getAdminUserId())
            .build();

        Realm desiredRealm = Realm.builder()
            .refName(realmId)
            .displayName(normalizedTenantDisplayName)
            .emailDomain(command.getTenantEmailDomain())
            .databaseName(realmId)
            .domainContext(dc)
            .dataDomain(dataDomain)
            .defaultAdminUserId(command.getAdminUserId())
            .defaultPerspective("TENANT_ADMIN")
            .setupStatus(RealmSetupStatus.NOT_STARTED)
            .setupCompletionPercent(0)
            .configuredSolutionCount(0)
            .readySolutionCount(0)
            .pendingSeedPackCount(0)
            .pendingMigrationCount(0)
            .setupSummary("Tenant provisioned. Bootstrap a solution to finish setup.")
            .setupLastUpdated(new Date())
            .build();

        PrincipalContext tenantPrincipal = new PrincipalContext.Builder()
            .withDefaultRealm(realmId)
            .withDataDomain(dataDomain)
            .withUserId(command.getAdminUserId())
            .withRoles(new String[]{"admin"})
            .withScope("TenantProvisioning")
            .build();

        ResourceContext migrationResource = new ResourceContext.Builder()
            .withRealm(realmId)
            .withArea("MIGRATION")
            .withFunctionalDomain("DB")
            .withAction("APPLY")
            .build();

        ResourceContext seedResource = new ResourceContext.Builder()
            .withRealm(realmId)
            .withArea("SEED")
            .withFunctionalDomain("SEED")
            .withAction("APPLY")
            .build();

        return ProvisioningContext.builder()
            .command(command)
            .result(result)
            .systemRealm(envConfigUtils.getSystemRealm())
            .realmId(realmId)
            .normalizedTenantDisplayName(normalizedTenantDisplayName)
            .domainContext(dc)
            .dataDomain(dataDomain)
            .desiredRealm(desiredRealm)
            .tenantPrincipal(tenantPrincipal)
            .migrationResource(migrationResource)
            .seedResource(seedResource)
            .emitter(createEmitter())
            .desiredRoles(Set.of("admin", "user"))
            .baselineAdminUserId("admin@" + command.getTenantEmailDomain())
            .demoUserId("demo@" + command.getTenantEmailDomain())
            .demoPassword(ConfigProvider.getConfig()
                .getOptionalValue("quantum.provisioning.default-demo-password", String.class)
                .filter(value -> !value.isBlank())
                .orElse(DEFAULT_DEMO_PASSWORD))
            .archetypes(normalizeArchetypes(command.getArchetypes()))
            .build();
    }

    public void ensureRealmCatalog(ProvisioningContext context) {
        Log.infof("  computed realmId: %s", context.getRealmId());
        Optional<Realm> existingOpt = realmRepo.findByEmailDomain(
            context.getCommand().getTenantEmailDomain(),
            true,
            context.getSystemRealm()
        );

        if (existingOpt.isPresent()) {
            Log.warnf("Realm %s already exists in the realm collection in realm %s", context.getRealmId(), context.getSystemRealm());
            Realm existing = existingOpt.get();
            List<String> diffs = new ArrayList<>();
            if (!Objects.equals(existing.getEmailDomain(), context.getDesiredRealm().getEmailDomain())) {
                diffs.add(String.format("emailDomain: existing='%s', requested='%s'",
                    existing.getEmailDomain(), context.getDesiredRealm().getEmailDomain()));
            }
            if (!Objects.equals(existing.getDatabaseName(), context.getDesiredRealm().getDatabaseName())) {
                diffs.add(String.format("databaseName: existing='%s', requested='%s'",
                    existing.getDatabaseName(), context.getDesiredRealm().getDatabaseName()));
            }
            if (!Objects.equals(existing.getDefaultAdminUserId(), context.getDesiredRealm().getDefaultAdminUserId())) {
                diffs.add(String.format("defaultAdminUserId: existing='%s', requested='%s'",
                    existing.getDefaultAdminUserId(), context.getDesiredRealm().getDefaultAdminUserId()));
            }
            if (!Objects.equals(existing.getDomainContext(), context.getDesiredRealm().getDomainContext())) {
                diffs.add(String.format("domainContext: existing='%s', requested='%s'",
                    existing.getDomainContext(), context.getDesiredRealm().getDomainContext()));
            }
            if (!Objects.equals(existing.getDefaultPerspective(), context.getDesiredRealm().getDefaultPerspective())) {
                diffs.add(String.format("defaultPerspective: existing='%s', requested='%s'",
                    existing.getDefaultPerspective(), context.getDesiredRealm().getDefaultPerspective()));
            }

            if (!diffs.isEmpty()) {
                throw new IllegalStateException("Realm already exists; differences detected: " + String.join("; ", diffs));
            }
            Log.warnf("Realm %s already exists in system catalog; proceeding idempotently.", context.getRealmId());
            context.getResult().addWarning("Realm already exists; no catalog changes were made.");
            return;
        }

        realmRepo.save(context.getSystemRealm(), context.getDesiredRealm());
        Log.infof("Created realm catalog entry for %s in system realm %s", context.getRealmId(), context.getSystemRealm());
        context.getResult().realmCreated = true;
    }

    public void ensureRealmMembership(ProvisioningContext context) {
        upsertRealmMembership(
            context.getSystemRealm(),
            context.getDesiredRealm(),
            context.getDomainContext(),
            context.getArchetypes()
        );
    }

    public void runRealmMigrations(ProvisioningContext context) {
        withDropPendingRetry(context.getRealmId(), "run migrations", () ->
            SecurityCallScope.runWithContexts(context.getTenantPrincipal(), context.getMigrationResource(), () ->
                migrationService.runAllUnRunMigrations(context.getRealmId(), context.getEmitter())
            )
        );
    }

    public void ensureTenantIdentities(ProvisioningContext context) {
        if (context.getCommand().getAdminPassword() == null || context.getCommand().getAdminPassword().isBlank()) {
            throw new IllegalArgumentException("adminPassword cannot be null");
        }

        UserManagement userManagement = authProviderFactory.getUserManager();
        boolean userExists = userManagement.userIdExists(context.getCommand().getAdminUserId());
        if (!userExists) {
            Log.warnf("Creating initial admin user %s with password provided, however since we are creating it the subject passed in %s will be ignored",
                context.getCommand().getAdminUserId(),
                context.getCommand().getAdminSubject());
            userManagement.createUser(
                context.getCommand().getAdminUserId(),
                context.getCommand().getAdminPassword(),
                Boolean.FALSE,
                context.getDesiredRoles(),
                context.getDomainContext()
            );
            context.getResult().userCreated = true;
        } else {
            Optional<CredentialUserIdPassword> credOpt = credentialRepo.findByUserId(
                context.getCommand().getAdminUserId(),
                context.getSystemRealm(),
                true
            );
            if (credOpt.isEmpty()) {
                throw new IllegalStateException(String.format(
                    "Admin user exists but credential with UserId: %s was not found in credential repo in realm: %s.",
                    context.getCommand().getAdminUserId(),
                    context.getSystemRealm()
                ));
            }
            CredentialUserIdPassword cred = credOpt.get();
            Set<String> storedRoles = cred.getRoles() == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(cred.getRoles()));
            boolean attributesMatch = Objects.equals(cred.getSubject(), context.getCommand().getAdminSubject())
                && Objects.equals(storedRoles, context.getDesiredRoles())
                && Objects.equals(Boolean.FALSE, cred.getForceChangePassword())
                && Objects.equals(cred.getDomainContext(), context.getDomainContext());
            if (!attributesMatch) {
                List<String> diffs = new ArrayList<>();
                if (!Objects.equals(cred.getSubject(), context.getCommand().getAdminSubject())) {
                    diffs.add(String.format("subject: existing='%s', requested='%s'",
                        cred.getSubject(), context.getCommand().getAdminSubject()));
                }
                if (!Objects.equals(storedRoles, context.getDesiredRoles())) {
                    diffs.add(String.format("roles: existing='%s', requested='%s'",
                        storedRoles, context.getDesiredRoles()));
                }
                if (!Objects.equals(Boolean.FALSE, cred.getForceChangePassword())) {
                    diffs.add(String.format("forceChangePassword: existing='%s', requested='%s'",
                        cred.getForceChangePassword(), Boolean.FALSE));
                }
                if (!Objects.equals(cred.getDomainContext(), context.getDomainContext())) {
                    diffs.add(String.format("domainContext: existing='%s', requested='%s'",
                        cred.getDomainContext(), context.getDomainContext()));
                }
                String text = String.format("Admin user already exists with different attributes. Diffs:%s",
                    String.join(System.lineSeparator(), diffs));
                if (!context.getCommand().isOverwriteAll()) {
                    throw new IllegalStateException(text);
                }
                Log.warn(text);
            }
            Log.warnf("Admin user %s already exists in realm %s; proceeding idempotently.",
                context.getCommand().getAdminUserId(),
                context.getRealmId());
            context.getResult().addWarning("Admin user already exists; no user changes were made.");
        }

        ensureUserProfileInRealm(
            context.getRealmId(),
            context.getCommand().getAdminUserId(),
            context.getCommand().getAdminUserId(),
            context.getDesiredRoles(),
            context.getDomainContext()
        );

        if (!context.getBaselineAdminUserId().equalsIgnoreCase(context.getCommand().getAdminUserId())) {
            ensureCredentialExists(
                context.getRealmId(),
                context.getBaselineAdminUserId(),
                context.getCommand().getAdminPassword(),
                context.getDesiredRoles(),
                context.getDomainContext(),
                context.getResult(),
                "Baseline tenant admin user"
            );
            ensureUserProfileInRealm(
                context.getRealmId(),
                context.getBaselineAdminUserId(),
                context.getBaselineAdminUserId(),
                context.getDesiredRoles(),
                context.getDomainContext()
            );
        }

        ensureCredentialExists(
            context.getRealmId(),
            context.getDemoUserId(),
            context.getDemoPassword(),
            Set.of("user"),
            context.getDomainContext(),
            context.getResult(),
            "Demo tenant user"
        );
        ensureUserProfileInRealm(
            context.getRealmId(),
            context.getDemoUserId(),
            context.getDemoUserId(),
            Set.of("user"),
            context.getDomainContext()
        );

        ensureTenantAdminMemberships(
            context.getRealmId(),
            context.getDomainContext(),
            context.getBaselineAdminUserId(),
            context.getCommand().getAdminUserId()
        );
    }

    public void applyBaseSeedPacks(ProvisioningContext context) {
        SeedContext seedContext = seedContext(context);
        Map<String, SeedPackDescriptor> latestByPack;
        try {
            latestByPack = seedDiscoveryService.discoverLatestApplicable(seedContext, null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover seed packs: " + e.getMessage(), e);
        }

        List<SeedPackRef> baseRefs = latestByPack.values().stream()
            .map(d -> SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion()))
            .toList();
        if (baseRefs.isEmpty()) {
            return;
        }

        Log.infof("Applying %d base seed pack(s) (GLOBAL/unscoped/per-tenant) to realm %s", baseRefs.size(), context.getRealmId());
        SecurityCallScope.runWithContexts(context.getTenantPrincipal(), context.getSeedResource(), () ->
            seedLoaderService.applySeeds(seedContext, baseRefs)
        );
    }

    public void applyRequestedArchetypes(ProvisioningContext context) {
        if (context.getArchetypes() == null || context.getArchetypes().isEmpty()) {
            Log.info("No Archetypes applicable");
            return;
        }

        SeedContext seedContext = seedContext(context);
        for (String archetype : context.getArchetypes()) {
            String at = archetype == null ? null : archetype.trim();
            if (at == null || at.isEmpty()) {
                continue;
            }
            Log.infof("Applying seed archetype '%s' to realm %s", at, context.getRealmId());
            SecurityCallScope.runWithContexts(context.getTenantPrincipal(), context.getSeedResource(), () ->
                seedLoaderService.applyArchetype(seedContext, at)
            );
            if (!context.getResult().appliedSeedArchetypes.contains(at)) {
                context.getResult().appliedSeedArchetypes.add(at);
            }
        }
    }

    public void verifyRealmInitialization(ProvisioningContext context) {
        withDropPendingRetry(context.getRealmId(), "apply indexes and verify initialization", () ->
            SecurityCallScope.runWithContexts(context.getTenantPrincipal(), context.getMigrationResource(), () -> {
                migrationService.applyIndexes(context.getRealmId());
                migrationService.checkInitialized(context.getRealmId());
            })
        );
    }

    private void ensureCredentialExists(String realmId,
                                        String userId,
                                        String password,
                                        Set<String> roles,
                                        DomainContext domainContext,
                                        ProvisionResult result,
                                        String label) {
        UserManagement userManagement = authProviderFactory.getUserManager();
        if (userManagement.userIdExists(userId)) {
            Log.infof("%s %s already exists for realm %s; provisioning will reuse it.", label, userId, realmId);
            result.addWarning(label + " already exists; provisioning reused the existing identity.");
            return;
        }

        userManagement.createUser(userId, password, Boolean.FALSE, roles, domainContext);
        Log.infof("Created %s %s for realm %s", label.toLowerCase(Locale.ROOT), userId, realmId);
    }

    private SeedContext seedContext(ProvisioningContext context) {
        return SeedContext.builder(context.getRealmId())
            .tenantId(context.getDomainContext().getTenantId())
            .orgRefName(context.getDomainContext().getOrgRefName())
            .accountId(context.getDomainContext().getAccountId())
            .ownerId(context.getCommand().getAdminUserId())
            .variable("adminUserId", context.getCommand().getAdminUserId())
            .variable("baselineAdminUserId", context.getBaselineAdminUserId())
            .variable("demoUserId", context.getDemoUserId())
            .build();
    }

    private void ensureUserProfileInRealm(String realmId,
                                          String userId,
                                          String email,
                                          Set<String> roles,
                                          DomainContext domainContext) {
        Optional<CredentialUserIdPassword> credential = credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true);
        if (credential.isEmpty()) {
            throw new IllegalStateException("Credential was not found for provisioned userId: " + userId);
        }

        EntityReference credentialRef = credential.get().createEntityReference(envConfigUtils.getSystemRealm());
        Optional<UserProfile> existing = userProfileRepo.getByUserId(realmId, userId);
        if (existing.isPresent()) {
            UserProfile profile = existing.get();
            boolean changed = false;
            if (!Objects.equals(profile.getCredentialUserIdPasswordRef(), credentialRef)) {
                profile.setCredentialUserIdPasswordRef(credentialRef);
                changed = true;
            }
            if (!Objects.equals(profile.getEmail(), email)) {
                profile.setEmail(email);
                changed = true;
            }
            if (!Objects.equals(profile.getUserId(), userId)) {
                profile.setUserId(userId);
                changed = true;
            }
            if (changed) {
                userProfileRepo.save(realmId, profile);
            }
            return;
        }

        UserProfile profile = UserProfile.builder()
                .refName(userId)
                .displayName(userId)
                .userId(userId)
                .email(email)
                .credentialUserIdPasswordRef(credentialRef)
                .dataDomain(DataDomain.builder()
                        .orgRefName(domainContext.getOrgRefName())
                        .accountNum(domainContext.getAccountId())
                        .tenantId(domainContext.getTenantId())
                        .ownerId(userId)
                        .build())
                .build();
        userProfileRepo.save(realmId, profile);
    }

    private void ensureTenantAdminMemberships(String realmId, DomainContext domainContext, String baselineAdminUserId, String requestedAdminUserId) {
        Optional<UserGroup> tenantAdminGroup = userGroupRepo.findByRefName(TENANT_ADMIN_GROUP_REF, realmId);
        UserGroup group = tenantAdminGroup.orElseGet(UserGroup::new);
        if (group.getRefName() == null || group.getRefName().isBlank()) {
            group.setRefName(TENANT_ADMIN_GROUP_REF);
        }
        if (group.getDisplayName() == null || group.getDisplayName().isBlank()) {
            group.setDisplayName(TENANT_ADMIN_GROUP_DISPLAY);
        }
        if (group.getDataDomain() == null) {
            group.setDataDomain(DataDomain.builder()
                    .orgRefName(domainContext.getOrgRefName())
                    .accountNum(domainContext.getAccountId())
                    .tenantId(domainContext.getTenantId())
                    .ownerId(baselineAdminUserId)
                    .build());
        }
        List<String> roles = group.getRoles() == null
                ? new ArrayList<>()
                : new ArrayList<>(group.getRoles());
        if (roles.stream().noneMatch(role -> "admin".equalsIgnoreCase(role))) {
            roles.add("admin");
            group.setRoles(roles);
        }
        List<EntityReference> members = group.getUserProfiles() == null
                ? new ArrayList<>()
                : new ArrayList<>(group.getUserProfiles());

        upsertUserProfileReference(members, baselineAdminUserId);
        if (requestedAdminUserId != null && !requestedAdminUserId.equalsIgnoreCase(baselineAdminUserId)) {
            upsertUserProfileReference(members, requestedAdminUserId);
        }

        group.setUserProfiles(members);
        userGroupRepo.save(realmId, group);
    }

    private void upsertUserProfileReference(List<EntityReference> members, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        boolean exists = members.stream()
                .filter(Objects::nonNull)
                .anyMatch(ref -> userId.equalsIgnoreCase(ref.getEntityRefName()));
        if (exists) {
            return;
        }

        members.add(EntityReference.builder()
                .entityRefName(userId)
                .entityDisplayName(userId)
                .entityType(UserProfile.class.getSimpleName())
                .build());
    }

    private void upsertRealmMembership(String systemRealm,
                                       Realm realm,
                                       DomainContext dc,
                                       List<String> archetypes) {
        String primaryArchetype = (archetypes != null && !archetypes.isEmpty()) ? archetypes.get(0) : null;
        String provisioningMode = primaryArchetype == null
                ? "CUSTOM"
                : (primaryArchetype.toLowerCase().contains("demo") ? "DEMO" : "PRODUCTION");

        String membershipRefName = String.join(":",
                java.util.List.of(
                        dc.getOrgRefName(),
                        dc.getAccountId(),
                        realm.getRefName()
                ));

        RealmTenantMembership membership = RealmTenantMembership.builder()
                .refName(membershipRefName)
                .displayName(realm.getDisplayName())
                .realmRefName(realm.getRefName())
                .realmDisplayName(realm.getDisplayName())
                .organizationRefName(dc.getOrgRefName())
                .accountId(dc.getAccountId())
                .tenantId(dc.getTenantId())
                .realmEmailDomain(realm.getEmailDomain())
                .defaultAdminUserId(realm.getDefaultAdminUserId())
                .realmEditionRefName(primaryArchetype)
                .provisioningMode(provisioningMode)
                .participationStatus("ACTIVE")
                .setupStatus(realm.getSetupStatus() == null ? null : realm.getSetupStatus().name())
                .setupCompletionPercent(realm.getSetupCompletionPercent())
                .dataDomain(DataDomain.builder()
                        .orgRefName(dc.getOrgRefName())
                        .accountNum(dc.getAccountId())
                        .tenantId(dc.getTenantId())
                        .ownerId(realm.getDefaultAdminUserId())
                        .build())
                .build();

        realmTenantMembershipRepo.save(systemRealm, membership);
    }

    public DeleteResult deleteTenant(String realmId) {
        Objects.requireNonNull(realmId, "realmId cannot be null");
        String normalizedRealmId = realmId.trim();
        if (normalizedRealmId.isBlank()) {
            throw new IllegalArgumentException("realmId cannot be blank");
        }
        if (envConfigUtils.getSystemRealm().equalsIgnoreCase(normalizedRealmId)) {
            throw new IllegalStateException("Refusing to delete the configured system realm");
        }

        String systemRealm = envConfigUtils.getSystemRealm();
        Realm realm = realmRepo.findByRefName(normalizedRealmId, true, systemRealm)
                .orElseThrow(() -> new IllegalStateException("Realm was not found in the system catalog: " + normalizedRealmId));

        DeleteResult result = new DeleteResult();
        result.realmId = normalizedRealmId;

        Datastore systemDatastore = credentialRepo.getMorphiaDataStoreWrapper().getDataStore(systemRealm);
        List<CredentialUserIdPassword> tenantCredentials = systemDatastore.find(CredentialUserIdPassword.class)
                .filter(Filters.or(
                        Filters.eq("domainContext.defaultRealm", normalizedRealmId),
                        Filters.eq("domainContext.tenantId", realm.getEmailDomain()),
                        Filters.eq("userId", realm.getDefaultAdminUserId())
                ))
                .iterator()
                .toList();

        for (CredentialUserIdPassword credential : tenantCredentials) {
            try {
                credentialRepo.delete(systemRealm, credential);
                result.deletedCredentialCount++;
            } catch (Exception e) {
                String warning = String.format("Failed to delete credential %s: %s", credential.getUserId(), e.getMessage());
                Log.warn(warning);
                result.addWarning(warning);
            }
        }

        try {
            mongoClient.getDatabase(normalizedRealmId).drop();
            result.databaseDropped = true;
        } catch (Exception e) {
            String warning = String.format("Failed to drop database %s: %s", normalizedRealmId, e.getMessage());
            Log.warn(warning);
            result.addWarning(warning);
        }

        try {
            Datastore systemRealmDatastore = realmRepo.getMorphiaDataStoreWrapper().getDataStore(systemRealm);
            long deletedCount = systemRealmDatastore.find(Realm.class)
                    .filter(Filters.eq("_id", realm.getId()))
                    .delete()
                    .getDeletedCount();
            result.realmCatalogDeleted = deletedCount > 0;
            if (!result.realmCatalogDeleted) {
                result.addWarning("Realm catalog entry was not deleted from the system catalog: " + normalizedRealmId);
            }
        } catch (Exception e) {
            String warning = String.format("Failed to delete realm catalog entry %s: %s", normalizedRealmId, e.getMessage());
            Log.warn(warning);
            result.addWarning(warning);
        }

        return result;
    }

    @FunctionalInterface
    interface ProvisioningAction {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private MultiEmitter<String> createEmitter() {
        return (MultiEmitter<String>) java.lang.reflect.Proxy.newProxyInstance(
            MultiEmitter.class.getClassLoader(),
            new Class[]{MultiEmitter.class},
            (proxy, method, args) -> {
                String m = method.getName();
                if ("emit".equals(m) && args != null && args.length == 1 && args[0] instanceof String s) {
                    Log.infof("[TenantProvisioning] %s", s);
                }
                Class<?> rt = method.getReturnType();
                if (rt == Void.TYPE) return null;
                if (rt == boolean.class || rt == Boolean.class) return Boolean.FALSE;
                if (rt == long.class || rt == Long.class) return 0L;
                if (MultiEmitter.class.isAssignableFrom(rt)) return proxy;
                return null;
            }
        );
    }

    private List<String> normalizeArchetypes(List<String> archetypes) {
        if (archetypes == null || archetypes.isEmpty()) {
            return List.of();
        }
        return archetypes.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private void withDropPendingRetry(String realmId, String operationName, ProvisioningAction action) {
        for (int attempt = 1; attempt <= DROP_PENDING_MAX_RETRIES; attempt++) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                if (!isDatabaseDropPending(e) || attempt == DROP_PENDING_MAX_RETRIES) {
                    throw asRuntimeException(e);
                }
                Log.warnf("Tenant provisioning for realm %s hit Mongo database drop pending while trying to %s (attempt %d/%d). Waiting %dms before retry.",
                        realmId, operationName, attempt, DROP_PENDING_MAX_RETRIES, DROP_PENDING_RETRY_DELAY_MS);
                sleepBeforeRetry();
            }
        }
    }

    private boolean isDatabaseDropPending(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MongoCommandException mongoCommandException && mongoCommandException.getErrorCode() == 215) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("DatabaseDropPending") || message.contains("in the process of being dropped"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(DROP_PENDING_RETRY_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for database drop to complete", interruptedException);
        }
    }

    private RuntimeException asRuntimeException(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(exception.getMessage(), exception);
    }
}
