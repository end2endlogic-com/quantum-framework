package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The DataDomain a realm's ontology (its TBox) is stored and looked up under.
 *
 * <p>It is the realm's registered domain: the {@code domainContext} of its realm record (tenant,
 * org, account, data segment), which is the domain the realm's users act in. A tenant admin who
 * publishes a model therefore writes it into their own tenant, and the write is checked like any
 * other. The key stays principal-independent: every caller (an HTTP request, the reindexer, a
 * startup thread) derives the same key for a realm from the registry, never from who is calling.</p>
 *
 * <p>A realm with no registered domain (not in the registry, or no registry in this deployment, as
 * in standalone tests) keeps the legacy key, every field set to the realm name.</p>
 *
 * <p>The registry is consulted through {@link #registry}, installed by
 * {@link RealmOntologyKeyResolver} at startup. Registered keys are cached for the life of the
 * process. Missing realms are never cached: another process may register one between requests.
 * The first registered lookup migrates legacy TBoxes before exposing the new key. Registry and
 * migration failures fail closed and are retried only on the next caller lookup.</p>
 */
public final class RealmOntologyKeys {

    private static volatile Function<String, Optional<DataDomain>> registry;
    private static volatile BiConsumer<String, DataDomain> onRegistered;
    private static final Map<String, DataDomain> CACHE = new ConcurrentHashMap<>();

    public enum FailureCode { REGISTRY_UNAVAILABLE, INCOMPLETE_DOMAIN, MIGRATION_FAILED }

    /** A lookup failure must never be interpreted as an unregistered realm or an empty TBox. */
    public static final class ResolutionException extends IllegalStateException {
        private final FailureCode code;

        private ResolutionException(FailureCode code, String realm, Throwable cause) {
            super(code + ": cannot resolve ontology domain for realm " + realm, cause);
            this.code = code;
        }

        public FailureCode getCode() { return code; }
    }

    private RealmOntologyKeys() {
    }

    /**
     * Install the registry lookup (realm -> registered domain) and the hook run once per realm
     * when its registered key is first used. The hook signals failure by throwing; it is then run
     * again on the realm's next lookup. Clears the cache.
     */
    public static void install(Function<String, Optional<DataDomain>> lookup,
                               BiConsumer<String, DataDomain> firstUse) {
        registry = lookup;
        onRegistered = firstUse;
        CACHE.clear();
    }

    /** Remove the registry (tests); every realm falls back to the legacy key. */
    public static void uninstall() {
        registry = null;
        onRegistered = null;
        CACHE.clear();
    }

    /** Look {@code realm} up again on next use (e.g. after it was registered or re-registered). */
    public static void refresh(String realm) {
        CACHE.remove(realm);
    }

    /** The registered key is visible only after its legacy records have migrated successfully. */
    public static DataDomain forRealm(String realm) {
        Function<String, Optional<DataDomain>> lookup = registry;
        if (lookup == null) {
            return legacy(realm);
        }
        // computeIfAbsent serializes first-use migration for a realm. Returning null does not
        // cache a miss, and throwing does not cache a failed lookup or partial migration.
        DataDomain key = CACHE.computeIfAbsent(realm, name -> {
            Optional<DataDomain> registered;
            try {
                registered = lookup.apply(name);
            } catch (RuntimeException e) {
                throw new ResolutionException(FailureCode.REGISTRY_UNAVAILABLE, name, e);
            }
            if (registered.isEmpty()) {
                return null;
            }
            if (!complete(registered.get())) {
                throw new ResolutionException(FailureCode.INCOMPLETE_DOMAIN, name, null);
            }
            DataDomain resolved = normalize(name, registered.get());
            BiConsumer<String, DataDomain> hook = onRegistered;
            if (hook != null) {
                try {
                    hook.accept(name, copy(resolved));
                } catch (RuntimeException e) {
                    throw new ResolutionException(FailureCode.MIGRATION_FAILED, name, e);
                }
            }
            return resolved;
        });
        return key == null ? legacy(realm) : copy(key);
    }

    /** The pre-registry key: every field is the realm name. */
    public static DataDomain legacy(String realm) {
        return new DataDomain(realm, realm, realm, 0, realm);
    }

    private static boolean complete(DataDomain d) {
        return d != null && notBlank(d.getTenantId()) && notBlank(d.getOrgRefName()) && notBlank(d.getAccountNum());
    }

    /** Only the fields the TBox lookup keys on, plus a fixed owner, so the key is the same for everyone. */
    private static DataDomain normalize(String realm, DataDomain d) {
        return new DataDomain(d.getOrgRefName(), d.getAccountNum(), d.getTenantId(), d.getDataSegment(), realm);
    }

    private static DataDomain copy(DataDomain d) {
        return new DataDomain(d.getOrgRefName(), d.getAccountNum(), d.getTenantId(), d.getDataSegment(), d.getOwnerId());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
