package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.RealmRegistrationConflictException;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EnvConfigUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SystemDirectory} backed by the local system-realm database via the
 * existing repositories — today's behavior, made explicit and configurable
 * (CONTROL_PLANE_SPLIT_DESIGN.md, Phase A/B).
 *
 * The system realm reference comes from {@code quantum.realmConfig.systemRealm}
 * (via {@link EnvConfigUtils}); it is a configured pointer with a default, not
 * a constant other classes should reference.
 *
 * Reads/writes use {@code ignoreRules=true}: these are system-level directory
 * operations performed by the framework itself, consistent with the call sites
 * this class replaces.
 *
 * Not a CDI bean — instantiated by {@link SystemDirectoryProducer} according
 * to {@code quantum.system.directory.mode}.
 */
public class LocalSystemDirectory implements SystemDirectory {

    private final RealmRepo realmRepo;
    private final CredentialRepo credentialRepo;
    private final EnvConfigUtils envConfigUtils;

    public LocalSystemDirectory(RealmRepo realmRepo,
                                CredentialRepo credentialRepo,
                                EnvConfigUtils envConfigUtils) {
        this.realmRepo = realmRepo;
        this.credentialRepo = credentialRepo;
        this.envConfigUtils = envConfigUtils;
    }

    @Override
    public String systemRealmId() {
        return envConfigUtils.getSystemRealm();
    }

    @Override
    public Optional<Realm> findRealmByEmailDomain(String emailDomain) {
        return realmRepo.findByEmailDomain(emailDomain, true, systemRealmId());
    }

    @Override
    public Optional<Realm> findRealmByRefName(String refName) {
        return realmRepo.findByRefName(refName, true, systemRealmId());
    }

    @Override
    public Realm registerRealm(Realm realm) {
        // Catalog writes are framework directory operations. MorphiaRepo.save
        // otherwise evaluates policy through QuantumAuthServiceAuthProvider, which
        // handshakes the issuer's /contract-hash. Auth bootstrap publishes realms
        // during StartupEvent, before that HTTP listener is up, so the handshake
        // times out, registerRealm returns 500, bootstrap swallows it, and every
        // later X-Realm mint fails with "Realm is not registered".
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            return realmRepo.save(systemRealmId(), mergeIntoRecordedRealm(realm));
        }
    }

    /**
     * Registering a realm is keyed by refName. A write that arrives without an id
     * (every control-plane PUT: the catalog contract carries no id) and names a
     * realm that is already recorded is applied to that record instead of
     * inserting a second document with the same refName.
     *
     * That upsert is deliberately narrow. It may set the display name, and the
     * owning application only while the recorded realm has none (backfill). It
     * never changes the fields that decide where and how the realm's data lives:
     * database name, connection string, deployment type, tenancy mode, email
     * domain, domain context and default admin. A write that asks for a
     * different value in any of them is refused with
     * {@link RealmRegistrationConflictException}. Writers round-trip the full
     * catalog entry (read, modify, write): deployment type and tenancy mode are
     * always compared, because the contract maps an absent value to
     * DEDICATED / SINGLE_TENANT and cannot express "unchanged". The string
     * fields and the domain context are compared only when the write carries
     * them. The default admin is compared unless it is only the value
     * {@link Realm#getDefaultAdminUserId()} derives from a missing email domain
     * ("admin@null"), so an explicit admin without an email domain is still
     * refused when it differs. Because the getter derives a value, a write that
     * carries the email domain but no admin is compared as "admin@domain" and is
     * refused when the recorded admin is explicit and different; full
     * round-trip writers ({@code ControlPlaneRealmMapper.toEntry}) always send
     * the recorded admin. Moving a realm to a different owner (compared ignoring case,
     * as provisioning does) is refused too: a realm belongs to exactly one
     * application.
     *
     * A write with no recorded realm is an insert. A racing duplicate insert in
     * the same data domain is refused by the {@code idx_refIdUniqueInDomain}
     * unique index rather than merged.
     */
    Realm mergeIntoRecordedRealm(Realm incoming) {
        if (incoming.getId() != null || incoming.getRefName() == null || incoming.getRefName().isBlank()) {
            return incoming;
        }
        Optional<Realm> found = realmRepo.findByRefName(incoming.getRefName(), true, systemRealmId());
        if (found.isEmpty()) {
            return incoming;
        }
        Realm recorded = found.get();
        String recordedOwner = ownerOf(recorded);
        String incomingOwner = ownerOf(incoming);
        if (recordedOwner != null && incomingOwner != null && !recordedOwner.equalsIgnoreCase(incomingOwner)) {
            throw new RealmRegistrationConflictException(
                RealmRegistrationConflictException.Kind.REALM_OWNER_CONFLICT, incoming.getRefName(),
                List.of("applicationRef"),
                "belongs to '" + recordedOwner + "', not '" + incomingOwner + "'");
        }
        List<String> refused = restrictedChanges(recorded, incoming);
        if (!refused.isEmpty()) {
            throw new RealmRegistrationConflictException(
                RealmRegistrationConflictException.Kind.REALM_RESTRICTED_FIELD_CHANGE, incoming.getRefName(),
                refused,
                "is already recorded; a catalog write may not change " + String.join(", ", refused));
        }
        if (recordedOwner == null && incomingOwner != null) {
            recorded.setApplicationRef(incoming.getApplicationRef());
        }
        if (hasText(incoming.getDisplayName())) {
            recorded.setDisplayName(incoming.getDisplayName());
        }
        return recorded;
    }

    private static List<String> restrictedChanges(Realm recorded, Realm incoming) {
        List<String> fields = new ArrayList<>();
        differs(fields, "databaseName", recorded.getDatabaseName(), incoming.getDatabaseName());
        differs(fields, "connectionString", recorded.getConnectionString(), incoming.getConnectionString());
        differs(fields, "emailDomain", recorded.getEmailDomain(), incoming.getEmailDomain());
        String incomingAdmin = incoming.getDefaultAdminUserId();
        boolean adminDerivedFromMissingDomain = !hasText(incoming.getEmailDomain())
            && ("admin@" + incoming.getEmailDomain()).equals(incomingAdmin);
        if (!adminDerivedFromMissingDomain) {
            differs(fields, "defaultAdminUserId", recorded.getDefaultAdminUserId(), incomingAdmin);
        }
        if (incoming.getDeploymentType() != recorded.getDeploymentType()) {
            fields.add("deploymentType");
        }
        if (incoming.getTenancyMode() != recorded.getTenancyMode()) {
            fields.add("tenancyMode");
        }
        if (incoming.getDomainContext() != null
                && !sameCatalogDomain(recorded.getDomainContext(), incoming.getDomainContext())) {
            fields.add("domainContext");
        }
        return fields;
    }

    /** A value the write leaves empty is not a change; a different non-empty value is. */
    private static void differs(List<String> fields, String name, String recorded, String incoming) {
        if (hasText(incoming) && !incoming.equals(recorded)) {
            fields.add(name);
        }
    }

    /** Compares the domain-context fields the catalog contract carries. */
    private static boolean sameCatalogDomain(DomainContext recorded, DomainContext incoming) {
        return recorded != null
            && Objects.equals(recorded.getTenantId(), incoming.getTenantId())
            && Objects.equals(recorded.getOrgRefName(), incoming.getOrgRefName())
            && Objects.equals(recorded.getAccountId(), incoming.getAccountId())
            && recorded.getDataSegment() == incoming.getDataSegment();
    }

    private static String ownerOf(Realm realm) {
        return realm.getApplicationRef() == null || !hasText(realm.getApplicationRef().getEntityRefName())
            ? null
            : realm.getApplicationRef().getEntityRefName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialBySubject(String subject) {
        return credentialRepo.findBySubject(subject, systemRealmId(), true);
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialByUserId(String userId) {
        return credentialRepo.findByUserId(userId, systemRealmId(), true);
    }
}
