package com.e2eq.framework.model.persistent.morphia.system;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.util.EnvConfigUtils;

import java.util.Optional;

/**
 * {@link SystemDirectory} backed by the local system-realm database via the
 * existing repositories — today's behavior, made explicit and configurable
 * (CONTROL_PLANE_SPLIT_DESIGN.md, Phase A).
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
        return realmRepo.save(systemRealmId(), realm);
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
