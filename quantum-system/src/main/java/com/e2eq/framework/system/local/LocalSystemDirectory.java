package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EnvConfigUtils;

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
     * (every control-plane PUT: the catalog contract carries no id) is applied to
     * the recorded realm instead of inserting a second document with the same
     * refName, which {@code findByRefName} would then shadow.
     *
     * Only the fields the catalog contract carries are copied onto the recorded
     * document, so state the contract does not carry (setup progress, seeding
     * flags, perspective, tags, audit) survives, and so do deploymentType and
     * tenancyMode, which a write cannot change. A field the write leaves empty
     * keeps its recorded value; in particular a caller built before
     * {@code applicationRefName} existed cannot erase the owner. Moving a realm to a
     * different owner is refused: a realm belongs to exactly one application.
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
        if (recordedOwner != null && incomingOwner != null && !recordedOwner.equals(incomingOwner)) {
            throw new IllegalStateException("REALM_OWNER_CONFLICT: realm '" + incoming.getRefName()
                + "' belongs to '" + recordedOwner + "', not '" + incomingOwner + "'");
        }
        if (incomingOwner != null) recorded.setApplicationRef(incoming.getApplicationRef());
        if (hasText(incoming.getDisplayName())) recorded.setDisplayName(incoming.getDisplayName());
        if (hasText(incoming.getDatabaseName())) recorded.setDatabaseName(incoming.getDatabaseName());
        if (hasText(incoming.getEmailDomain())) recorded.setEmailDomain(incoming.getEmailDomain());
        if (hasText(incoming.getDefaultAdminUserId())) recorded.setDefaultAdminUserId(incoming.getDefaultAdminUserId());
        if (hasText(incoming.getConnectionString())) recorded.setConnectionString(incoming.getConnectionString());
        if (incoming.getDomainContext() != null) recorded.setDomainContext(incoming.getDomainContext());
        // Topology and tenancy are not changed by a catalog write: the contract maps
        // an absent value to DEDICATED / SINGLE_TENANT, so a caller that omits them
        // would otherwise turn a pooled realm into a dedicated one.
        return recorded;
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
