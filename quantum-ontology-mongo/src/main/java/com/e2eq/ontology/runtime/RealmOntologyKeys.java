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
 * process (a realm's domain does not change); an unregistered realm is re-checked after
 * {@link #UNREGISTERED_RECHECK_MILLIS}. The first time a registered key is used for a realm,
 * {@link #onRegistered} moves TBoxes stored under the legacy key to it.</p>
 */
public final class RealmOntologyKeys {

    static final long UNREGISTERED_RECHECK_MILLIS = 60_000L;

    private static volatile Function<String, Optional<DataDomain>> registry;
    private static volatile BiConsumer<String, DataDomain> onRegistered;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(DataDomain key, boolean registered, long checkedAt) {
    }

    private RealmOntologyKeys() {
    }

    /**
     * Install the registry lookup (realm -> registered domain) and the hook run once per realm
     * when its registered key is first used. Clears the cache.
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

    /** The key for {@code realm}: its registered domain, or the legacy key when unregistered. */
    public static DataDomain forRealm(String realm) {
        Entry cached = CACHE.get(realm);
        long now = System.currentTimeMillis();
        if (cached != null && (cached.registered() || now - cached.checkedAt() < UNREGISTERED_RECHECK_MILLIS)) {
            return copy(cached.key());
        }
        Function<String, Optional<DataDomain>> lookup = registry;
        Optional<DataDomain> registered = Optional.empty();
        if (lookup != null) {
            try {
                registered = lookup.apply(realm).filter(RealmOntologyKeys::complete);
            } catch (RuntimeException e) {
                // A registry outage must not move a realm's model to another key; keep the
                // previous answer if there is one, otherwise the legacy key, and re-check soon.
                if (cached != null) {
                    return copy(cached.key());
                }
            }
        }
        if (registered.isEmpty()) {
            DataDomain legacy = legacy(realm);
            CACHE.put(realm, new Entry(legacy, false, now));
            return copy(legacy);
        }
        DataDomain key = normalize(realm, registered.get());
        Entry previous = CACHE.put(realm, new Entry(key, true, now));
        if (previous == null || !previous.registered()) {
            BiConsumer<String, DataDomain> hook = onRegistered;
            if (hook != null) {
                hook.accept(realm, copy(key));
            }
        }
        return copy(key);
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
