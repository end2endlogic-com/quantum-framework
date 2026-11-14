package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;

import java.util.Objects;
import java.util.Optional;

/**
 * Directory for a service that keeps its own system realm but reads the realm
 * catalog from the environment's directory owner ({@code quantum.mode=embedded}
 * with {@code quantum.directory.mode=remote}).
 *
 * <ul>
 *   <li>{@link #systemRealmId()} and the credential lookups stay local: the
 *   service's own records live in its own system realm.</li>
 *   <li>Realm catalog reads and registrations go to the directory owner, so a
 *   realm exists once, in {@code quantum-auth-service}.</li>
 * </ul>
 *
 * <p>There is no fallback between the two. A catalog call that fails remotely
 * fails; it is never answered from the local copy.</p>
 */
public class SplitSystemDirectory implements SystemDirectory {

    private final SystemDirectory local;
    private final SystemDirectory catalog;

    public SplitSystemDirectory(SystemDirectory local, SystemDirectory catalog) {
        this.local = Objects.requireNonNull(local, "local");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public String systemRealmId() {
        return local.systemRealmId();
    }

    @Override
    public Optional<Realm> findRealmByEmailDomain(String emailDomain) {
        return catalog.findRealmByEmailDomain(emailDomain);
    }

    @Override
    public Optional<Realm> findRealmByRefName(String refName) {
        return catalog.findRealmByRefName(refName);
    }

    @Override
    public Realm registerRealm(Realm realm) {
        return catalog.registerRealm(realm);
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialBySubject(String subject) {
        return local.findCredentialBySubject(subject);
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialByUserId(String userId) {
        return local.findCredentialByUserId(userId);
    }
}
