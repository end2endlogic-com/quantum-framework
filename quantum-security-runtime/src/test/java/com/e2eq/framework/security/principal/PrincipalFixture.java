package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.security.AccountType;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationRole;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.ServiceKind;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.security.identity.SecurityIdentity;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An in-memory credential store, realm catalog and membership directory shared by the
 * resolver's parity and refusal tests. Every collaborator the credential path touches reads
 * these maps, so a test changes the world by editing them.
 *
 * <p>World: application {@code acme-orders}; realms {@code acme-D1} and {@code acme-D2}
 * (dedicated, home application acme-orders) and {@code pooled} (shared); identities alice
 * (user), admin (may impersonate), svc-app (application service) and svc-deleg (delegating
 * service, may act for alice).
 */
public final class PrincipalFixture {

    public static final String APP = "acme-orders";
    public static final String SYSTEM_REALM = "system";

    public final Map<String, CredentialUserIdPassword> credentials = new HashMap<>();
    public final Map<String, Realm> realms = new HashMap<>();
    public final Map<String, UserRealmRole> assignments = new HashMap<>();
    public final List<RealmTenantMembership> memberships = new ArrayList<>();
    /** Application bindings, registered applications, installs ("app|realm|tenant") and group members. */
    public final List<UserAppRole> bindings = new ArrayList<>();
    public final Map<String, Application> applications = new HashMap<>();
    public final Set<String> installs = new LinkedHashSet<>();
    public final Map<String, List<String>> groupsOfUser = new HashMap<>();
    /** Owner tenant per group; {@link #bindGroup} sets the bound tenant when none is set. */
    public final Map<String, String> groupOwners = new HashMap<>();
    /** The binding store is held by a remote directory. */
    public boolean remoteDirectory;

    /** The deployment's configured membership-administrator roles. */
    public List<String> membershipAdminRoles = List.of();
    /** Every applicationId the role resolver was asked about. */
    public final List<String> roleApplications = new ArrayList<>();

    public final EnvConfigUtils env = new EnvConfigUtils();
    public final SecurityUtils securityUtils = new SecurityUtils();

    public final CredentialRepo credentialRepo = new FixtureCredentials(this);

    @jakarta.enterprise.inject.Vetoed
    static final class FixtureCredentials extends CredentialRepo {
        private final Map<String, CredentialUserIdPassword> credentials;

        FixtureCredentials(PrincipalFixture world) {
            this.credentials = world.credentials;
        }

        @Override
        public Optional<CredentialUserIdPassword> findBySubject(String subject, String realmId, boolean ignoreRules) {
            return credentials.values().stream().filter(c -> subject.equals(c.getSubject())).findFirst();
        }

        @Override
        public Optional<CredentialUserIdPassword> findByUserId(String userId, String realmId, boolean ignoreRules) {
            return credentials.values().stream().filter(c -> userId.equals(c.getUserId())).findFirst();
        }

        @Override
        public String getDatabaseName() {
            return SYSTEM_REALM;
        }
    }

    public final SystemDirectory directory = new SystemDirectory() {
        @Override public String systemRealmId() { return SYSTEM_REALM; }
        @Override public Optional<Realm> findRealmByEmailDomain(String emailDomain) { return Optional.empty(); }
        @Override public Optional<Realm> findRealmByRefName(String refName) {
            return Optional.ofNullable(refName == null ? null : realms.get(refName));
        }
        @Override public Realm registerRealm(Realm realm) { throw new UnsupportedOperationException(); }
        @Override public Optional<CredentialUserIdPassword> findCredentialBySubject(String subject) {
            return Optional.empty();
        }
        @Override public Optional<CredentialUserIdPassword> findCredentialByUserId(String userId) {
            return Optional.empty();
        }
    };

    public final RealmMembershipService membership = new FixtureMemberships(this);

    @jakarta.enterprise.inject.Vetoed
    static final class FixtureMemberships extends RealmMembershipService {
        private final Map<String, UserRealmRole> assignments;
        private final List<RealmTenantMembership> memberships;

        FixtureMemberships(PrincipalFixture world) {
            this.assignments = world.assignments;
            this.memberships = world.memberships;
        }

        @Override
        public Optional<UserRealmRole> assignmentForUser(String userId, String realmRefName) {
            return Optional.ofNullable(assignments.get(userId + "|" + realmRefName))
                    .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()));
        }

        @Override
        public List<UserRealmRole> realmsForUser(String userId) {
            return assignments.values().stream().filter(a -> userId.equals(a.getUserId())).toList();
        }

        @Override
        public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
            return memberships.stream().filter(m -> realmRefName.equals(m.getRealmRefName())).toList();
        }

        @Override
        public Optional<RealmTenantMembership> tenantInRealm(String realmRefName, String tenantId) {
            return membersOfRealm(realmRefName).stream()
                    .filter(m -> tenantId.equals(m.getTenantId()))
                    .filter(m -> !RealmTenantMembership.PARTICIPATION_STATUS_SUSPENDED.equals(m.getParticipationStatus()))
                    .findFirst();
        }
    }

    /**
     * Deterministic stand-in for {@link IdentityRoleResolver}: token roles only in the authority
     * realm, flat credential roles, then the effective realm assignment's roles.
     */
    public final IdentityRoleResolver roleResolver = new FixtureRoles(this);

    @jakarta.enterprise.inject.Vetoed
    static final class FixtureRoles extends IdentityRoleResolver {
        private final PrincipalFixture world;

        FixtureRoles(PrincipalFixture world) {
            this.world = world;
        }

        @Override
        public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential,
                                              String authorityRealm, String effectiveRealm, String applicationId) {
            world.roleApplications.add(applicationId);
            Set<String> roles = new LinkedHashSet<>();
            if (identity != null && Objects.equals(authorityRealm, effectiveRealm)) {
                roles.addAll(identity.getRoles());
            }
            if (credential.getRoles() != null) {
                roles.addAll(List.of(credential.getRoles()));
            }
            UserRealmRole assignment = world.assignments.get(credential.getUserId() + "|" + effectiveRealm);
            if (assignment != null && assignment.getRoles() != null) {
                roles.addAll(assignment.getRoles());
            }
            return roles.isEmpty() ? new String[]{"ANONYMOUS"} : roles.toArray(String[]::new);
        }
    }

    public final ApplicationAccessDirectory access = new FixtureAccess(this);

    @jakarta.enterprise.inject.Vetoed
    static final class FixtureAccess extends ApplicationAccessDirectory {
        private final PrincipalFixture world;

        FixtureAccess(PrincipalFixture world) {
            this.world = world;
        }

        @Override
        public List<UserAppRole> bindingsFor(CredentialUserIdPassword credential, String applicationId, String realm) {
            List<String> groups = world.groupsOfUser.getOrDefault(credential.getUserId(), List.of());
            return world.bindings.stream()
                    .filter(b -> Objects.equals(applicationId, b.getApplicationId()))
                    .filter(b -> realm == null || realm.equals(b.getRealmRefName()))
                    .filter(b -> credential.getUserId().equals(b.getUserId())
                            || (b.getGroupRefName() != null && groups.contains(b.getGroupRefName())))
                    .toList();
        }

        @Override
        public Optional<Application> application(String refName) {
            return Optional.ofNullable(world.applications.get(refName));
        }

        @Override
        public boolean bindingsReadable() {
            return !world.remoteDirectory;
        }

        @Override
        public List<String> alsoReservedRoles() {
            return world.membershipAdminRoles;
        }

        @Override
        public String groupOwnerTenant(String groupRefName) {
            return world.groupOwners.get(groupRefName);
        }

        @Override
        public boolean installed(String applicationId, String realm, String tenantId) {
            return world.installs.contains(applicationId + "|" + realm + "|" + tenantId);
        }
    }

    /** Registers {@code refName} declaring {@code roles}, each grantable by a tenant admin. */
    public Application declare(String refName, String... roles) {
        Application application = new Application();
        application.setRefName(refName);
        for (String role : roles) {
            application.getRoles().add(ApplicationRole.builder().name(role)
                    .grantableBy(List.of(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)).build());
        }
        applications.put(refName, application);
        return application;
    }

    public void install(String applicationId, String realm, String tenant) {
        installs.add(applicationId + "|" + realm + "|" + tenant);
    }

    /** A user binding. */
    public UserAppRole bind(String userId, String applicationId, String realm, List<String> tenants,
                            String defaultTenant, String... roles) {
        UserAppRole binding = new UserAppRole();
        binding.setUserId(userId);
        binding.setApplicationId(applicationId);
        binding.setRealmRefName(realm);
        binding.setTenantIds(new ArrayList<>(tenants));
        binding.setDefaultTenantId(defaultTenant);
        binding.setRoles(new ArrayList<>(List.of(roles)));
        binding.setStatus(UserAppRole.STATUS_ACTIVE);
        bindings.add(binding);
        return binding;
    }

    /** A group binding; the group is owned by {@code tenant} unless an owner is already set. */
    public UserAppRole bindGroup(String group, String applicationId, String realm, String tenant, String... roles) {
        groupOwners.putIfAbsent(group, tenant);
        UserAppRole binding = new UserAppRole();
        binding.setGroupRefName(group);
        binding.setApplicationId(applicationId);
        binding.setRealmRefName(realm);
        binding.setTenantIds(new ArrayList<>(List.of(tenant)));
        binding.setRoles(new ArrayList<>(List.of(roles)));
        bindings.add(binding);
        return binding;
    }

    public PrincipalFixture() {
        env.setSystemRealm(SYSTEM_REALM);
        realm("acme-D1", RealmTenancyMode.SINGLE_TENANT, "acme", "acme-org", "acme-acct");
        realm("acme-D2", RealmTenancyMode.SINGLE_TENANT, "acme-two", "acme-two-org", "acme-two-acct");
        realm("pooled", RealmTenancyMode.MULTI_TENANT, "pooled-default", "pool-org", "pool-acct");
        member("pooled", "tenant-a", "org-a", "acct-a");
        member("pooled", "tenant-b", "org-b", "acct-b");

        CredentialUserIdPassword alice = credential("alice@acme", "sub-alice", "acme-D1", "acme", "user");
        alice.setArea2RealmOverrides(Map.of("integration", "acme-archive"));
        alice.setDataDomainPolicy(policy(DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL, null));
        alice.setAuthorizedRealms(List.of(realmEntry("acme-D2"), realmEntry("pooled")));
        assign("alice@acme", "acme-D1", List.of("d1-user"));
        assign("alice@acme", "acme-D2", List.of("d2-user"));
        UserRealmRole pooled = assign("alice@acme", "pooled", List.of("pool-user"));
        pooled.setAuthorizedTenantIds(List.of("tenant-a", "tenant-b"));

        CredentialUserIdPassword admin = credential("admin@acme", "sub-admin", "acme-D1", "acme", "admin");
        admin.setImpersonateFilterScript("true");
        admin.setRealmRegEx("acme-*");

        CredentialUserIdPassword app = credential("svc-app@acme", "sub-svc-app", "acme-D1", "acme", "service");
        app.setCredentialType(CredentialType.SERVICE_TOKEN);
        app.setAccountType(AccountType.SERVICE);
        app.setServiceKind(ServiceKind.APPLICATION);

        CredentialUserIdPassword deleg = credential("svc-deleg@acme", "sub-svc-deleg", "acme-D1", "acme", "service");
        deleg.setCredentialType(CredentialType.SERVICE_TOKEN);
        deleg.setAccountType(AccountType.SERVICE);
        deleg.setServiceKind(ServiceKind.DELEGATING);
        CredentialUserIdPassword.ImpersonateEntry forAlice = new CredentialUserIdPassword.ImpersonateEntry();
        forAlice.setUserId("alice@acme");
        deleg.setAuthorizedImpersonateIds(List.of(forAlice));
        deleg.setRealmRegEx("acme-*|pooled");
    }

    public PrincipalContextResolver resolver() {
        return new PrincipalContextResolver(directory, membership, credentialRepo, roleResolver, securityUtils, env,
                new ImpersonationScriptEvaluator(), access);
    }

    public CredentialUserIdPassword credential(String userId) {
        return credentials.get(userId);
    }

    public CredentialUserIdPassword credential(String userId, String subject, String realm, String tenant,
                                               String... roles) {
        CredentialUserIdPassword credential = CredentialUserIdPassword.builder()
                .userId(userId)
                .subject(subject)
                .domainContext(DomainContext.builder()
                        .defaultRealm(realm).tenantId(tenant).orgRefName(tenant + "-org").accountId(tenant + "-acct")
                        .build())
                .lastUpdate(new Date())
                .roles(roles)
                .build();
        credentials.put(userId, credential);
        return credential;
    }

    public Realm realm(String refName, RealmTenancyMode mode, String tenant, String org, String account) {
        Realm realm = Realm.builder()
                .refName(refName)
                .emailDomain(refName + ".example.test")
                .databaseName(refName)
                .tenancyMode(mode)
                .domainContext(DomainContext.builder()
                        .defaultRealm(refName).tenantId(tenant).orgRefName(org).accountId(account).build())
                .build();
        realm.setApplicationRef(EntityReference.builder().entityRefName(APP).entityDisplayName(APP).build());
        realms.put(refName, realm);
        return realm;
    }

    public UserRealmRole assign(String userId, String realm, List<String> roles) {
        UserRealmRole assignment = UserRealmRole.builder()
                .userId(userId)
                .realmRefName(realm)
                .roles(new ArrayList<>(roles))
                .status(UserRealmRole.STATUS_ACTIVE)
                .build();
        assignments.put(userId + "|" + realm, assignment);
        return assignment;
    }

    public void member(String realm, String tenant, String org, String account) {
        memberships.add(RealmTenantMembership.builder()
                .realmRefName(realm)
                .tenantId(tenant)
                .organizationRefName(org)
                .accountId(account)
                .participationStatus(RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)
                .build());
    }

    public static DataDomainPolicy policy(DataDomainPolicyEntry.ResolutionMode mode, String fixedTenant) {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(mode);
        if (fixedTenant != null) {
            entry.setDataDomains(List.of(DataDomain.builder()
                    .tenantId(fixedTenant).orgRefName("org").accountNum("acct").ownerId("owner").build()));
        }
        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(new HashMap<>(Map.of("*:*", entry)));
        return policy;
    }

    static CredentialUserIdPassword.RealmEntry realmEntry(String realm) {
        CredentialUserIdPassword.RealmEntry entry = new CredentialUserIdPassword.RealmEntry();
        entry.setRealmRefName(realm);
        return entry;
    }

    /** An identity-only request for {@code userId}'s credential, for {@link #APP}. */
    public PrincipalContextRequest.Builder identity(String userId) {
        CredentialUserIdPassword credential = credentials.get(userId);
        return PrincipalContextRequest.identity(credential.getSubject(), credential.getUserId()).applicationId(APP);
    }
}
