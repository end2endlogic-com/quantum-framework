package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.mongo.MorphiaOntologyLoader;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realm-aware ontology registry provider that maintains separate ontology instances per realm (database).
 * <p>This provider supports true multi-tenancy by:</p>
 * <ul>
 *   <li>Caching OntologyRegistry instances per realm (MongoDB database)</li>
 *   <li>Building the TBox from annotations, the Morphia scan and YAML when no admitted TBox is active</li>
 *   <li>Checking each build's stable relations against the last build ({@link SourceTBoxGate})
 *       and persisting it in realm-specific collections</li>
 * </ul>
 * <p>
 * The realm is derived from SecurityContext via RuleContext.getRealmId().
 * TBox is per-realm (database-level), while ABox (edges) can be further scoped by DataDomain.
 * </p>
 */
@ApplicationScoped
public class TenantOntologyRegistryProvider {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    RuleContext ruleContext;

    @Inject
    OntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyTBoxRepo tenantTboxRepo;

    @Inject
    OntologyMetaService metaService;

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
    String defaultRealm;

    /**
     * Cluster cache-coherence window (ms). A cached realm registry is re-checked for a
     * newer admission at most once per this interval, via a cheap projected active-hash
     * query, so an {@code activate()} on one instance becomes visible on the others.
     * <ul>
     *   <li>{@code > 0} (default 5000): check at most once per window — bounds both
     *       staleness and DB load.</li>
     *   <li>{@code 0}: check on every read (strongest coherence, one small query/read).</li>
     *   <li>{@code < 0}: never check — legacy in-process-only invalidation behavior.</li>
     * </ul>
     */
    @ConfigProperty(name = "quantum.ontology.cache.staleness-check-interval-ms", defaultValue = "5000")
    long stalenessCheckIntervalMs;

    // Cache of ontology registries per realm
    private final Map<String, OntologyRegistry> realmRegistries = new ConcurrentHashMap<>();

    // Cache of ontology registries per DataDomain (for DataDomain-specific TBoxes)
    private final Map<String, OntologyRegistry> dataDomainRegistries = new ConcurrentHashMap<>();

    // Cluster coherence stamps for realmRegistries: the active-admission tboxHash each
    // cached entry reflects (empty string == "no active tenant TBox at load time"), and
    // the last time we checked that realm against the durable store.
    private final Map<String, String> realmActiveHash = new ConcurrentHashMap<>();
    private final Map<String, Long> realmLastCheckedAt = new ConcurrentHashMap<>();

    /** A refused source build, rethrown without a rescan until {@code retryAt}. */
    private record Refusal(SourceTBoxGate.RefusedException error, long retryAt) {}

    // Refused source builds per realm, so a refused realm does not rescan its sources on every read.
    private final Map<String, Refusal> realmRefusals = new ConcurrentHashMap<>();

    /**
     * Get the ontology registry for the current security context realm.
     * <p>
     * Derives the realm from the current SecurityContext and PrincipalContext.
     * Falls back to defaultRealm if no context is available.
     * </p>
     * @return OntologyRegistry for the current realm
     */
    public OntologyRegistry getRegistry() {
        String realm = getCurrentRealm();
        return getRegistryForRealm(realm);
    }

    /**
     * B5 vocabulary tier: resolve the THREE-state admitted-predicate disposition
     * for policy (security-rule) use in the current security-context realm.
     * <p>
     * Resolves the realm the SAME way {@link #getRegistry()} does (via the current
     * SecurityContext, falling back to {@code defaultRealm}), then reads the realm's
     * ACTIVE {@link TenantOntologyTBox} fresh (NOT cached — admission state changes
     * out-of-band). Returns:
     * </p>
     * <ul>
     *   <li>{@link AdmittedVocabularyResult#governed(java.util.Set)} when an active
     *       tenant TBox exists and its {@code admittedPredicates} field is non-null
     *       (governed realm); the set defines exactly which declared predicates may
     *       be referenced by rules. Decided at the {@code admitted != null} branch
     *       below.</li>
     *   <li>{@link AdmittedVocabularyResult#legacy()} when there is no active tenant
     *       doc, or a legacy doc with a {@code null admittedPredicates} field —
     *       meaning "all declared predicates admitted" (back-compat). Decided at the
     *       fall-through {@code return legacy()} below.</li>
     *   <li>{@link AdmittedVocabularyResult#unavailable()} when the read THREW (the
     *       {@code catch (Throwable)} branch). This is the security-critical case:
     *       the caller (policy guard) must fail CLOSED rather than treat the blip as
     *       legacy/all-admitted. We do NOT silently downgrade a GOVERNED realm on a
     *       transient Mongo failure.</li>
     * </ul>
     * <p>Read fresh on every call and fully null-safe.</p>
     *
     * <p><b>Availability tradeoff (surfaced for review):</b> mapping the read
     * failure to UNAVAILABLE means that during a Mongo outage, security-rule
     * (re)loading for a GOVERNED realm is rejected (fail CLOSED). This is a
     * deliberate trade of availability for security — it prevents a transient blip
     * from re-opening every declared predicate. It intentionally does NOT add a
     * bounded stale-TTL cache (B4, out of scope); it only fails closed and emits an
     * observable signal so the downgrade-attempt is visible.</p>
     *
     * @return the 3-state admitted-vocabulary disposition for the current realm
     */
    public AdmittedVocabularyResult admittedVocabularyForCurrentRealm() {
        String realm = getCurrentRealm();
        // realmDataDomain(realm) is pure logic — compute it OUTSIDE the try so a bug there
        // surfaces normally rather than being masked as a read failure (UNAVAILABLE).
        var dataDomain = realmDataDomain(realm);
        Optional<TenantOntologyTBox> activeTenantTBox;
        try {
            // ONLY the Mongo read may signal UNAVAILABLE. The null-check / GOVERNED-vs-LEGACY
            // construction below is pure logic and must NOT be reclassified as a read failure,
            // or a programming bug would masquerade as a transient outage.
            activeTenantTBox = tenantTboxRepo.findActiveTBox(dataDomain);
        } catch (Throwable t) {
            // UNAVAILABLE: the read threw. Do NOT collapse to legacy/all-admitted —
            // that is the fail-OPEN security downgrade this method exists to close.
            // Log WITH the throwable (stack) so the fail-closed event is diagnosable
            // (t.getMessage() is often null, e.g. for NPEs).
            Log.warn("admittedVocabularyForCurrentRealm: admitted-set read FAILED for realm " + realm
                    + "; returning UNAVAILABLE so the policy guard fails CLOSED (no predicates admitted "
                    + "for rule references) instead of silently downgrading to all-admitted", t);
            return AdmittedVocabularyResult.unavailable();
        }
        if (activeTenantTBox.isPresent()) {
            java.util.Set<String> admitted = activeTenantTBox.get().getAdmittedPredicates();
            if (admitted != null) {
                // GOVERNED: enforce exactly this set for rule references.
                return AdmittedVocabularyResult.governed(admitted);
            }
        }
        // LEGACY: no active doc, or active doc with null admittedPredicates.
        return AdmittedVocabularyResult.legacy();
    }

    /**
     * Legacy 2-state projection of {@link #admittedVocabularyForCurrentRealm()}:
     * GOVERNED -&gt; the admitted set, LEGACY and UNAVAILABLE -&gt; {@code empty()}.
     * <p>
     * <b>Do NOT use this from the policy guard.</b> It cannot distinguish the
     * read-failure (UNAVAILABLE) state from legacy and therefore re-opens the
     * fail-OPEN downgrade. It is retained only for any back-compat caller that
     * predates the 3-state contract; new code must call
     * {@link #admittedVocabularyForCurrentRealm()} and fail closed on UNAVAILABLE.
     * </p>
     * @return the admitted-predicate set for the current realm, or empty for legacy
     * @deprecated prefer {@link #admittedVocabularyForCurrentRealm()} which exposes
     *             the UNAVAILABLE state required to fail closed.
     */
    @Deprecated
    public java.util.Optional<java.util.Set<String>> admittedPredicatesForCurrentRealm() {
        return admittedVocabularyForCurrentRealm().asLegacyOptional();
    }

    /**
     * Get the ontology registry for a specific realm.
     * <p>
     * If no cached registry exists, loads the realm's active admitted TBox, or else builds
     * from annotations, the Morphia scan and the YAML overlay. A source build that breaks a
     * stable relation throws {@link SourceTBoxGate.StableContractException} (an unreadable baseline,
     * {@link SourceTBoxGate.BaselineUnreadableException}); the registry is not cached and the refusal
     * is rethrown without a rescan for {@code quantum.ontology.stable-diff.retry-after-ms}.
     * </p>
     * @param realm the realm (database name) to get registry for
     * @return OntologyRegistry for the realm
     */
    public OntologyRegistry getRegistryForRealm(String realm) {
        // Cluster coherence: before serving a cached entry, cheaply check (throttled)
        // whether a newer TBox has been activated on any instance and drop it if so.
        if (realmRegistries.containsKey(realm)) {
            maybeInvalidateIfStale(realm);
        }
        Refusal refusal = realmRefusals.get(realm);
        if (refusal != null) {
            if (System.currentTimeMillis() < refusal.retryAt()) throw refusal.error();
            realmRefusals.remove(realm, refusal);
        }
        try {
            return loadAndCache(realm);
        } catch (SourceTBoxGate.RefusedException e) {
            long retryAfterMs = ConfigProvider.getConfig()
                    .getOptionalValue("quantum.ontology.stable-diff.retry-after-ms", Long.class).orElse(30_000L);
            if (retryAfterMs > 0) {
                realmRefusals.put(realm, new Refusal(e, System.currentTimeMillis() + retryAfterMs));
            }
            throw e;
        }
    }

    private OntologyRegistry loadAndCache(String realm) {
        return realmRegistries.computeIfAbsent(realm, r -> {
            Log.infof("Loading ontology registry for realm: %s", r);
            OntologyRegistry registry = loadOrBuildRegistryForRealm(r);
            // Snapshot the active-admission hash this entry reflects so later reads on
            // this (or any) instance can detect a subsequent activation. Skipped entirely
            // when coherence checking is disabled (<0) so that path does ZERO extra probes
            // — genuinely equivalent to legacy in-process-only behavior.
            if (stalenessCheckIntervalMs >= 0) {
                realmActiveHash.put(r, activeHashOrEmpty(r));
                realmLastCheckedAt.put(r, System.currentTimeMillis());
            }
            return registry;
        });
    }

    /**
     * The canonical hash of the realm's ACTIVE tenant TBox, or {@code ""} when none is
     * active. Empty string is used (never null) so it is safe as a {@link ConcurrentHashMap}
     * value and compares cleanly against a cached stamp.
     */
    private String activeHashOrEmpty(String realm) {
        try {
            return tenantTboxRepo.findActiveTBoxHash(realmDataDomain(realm)).orElse("");
        } catch (RealmOntologyKeys.ResolutionException e) {
            throw e;
        } catch (Throwable t) {
            // A probe failure must never break registry loading; treat as "unknown" so the
            // next check re-probes rather than pinning a bad stamp.
            Log.warnf("Realm %s: active-hash probe failed (%s); leaving coherence stamp unset",
                    realm, t.getMessage());
            return "";
        }
    }

    /**
     * Throttled cluster cache-coherence check. If the realm's active-admission hash in the
     * durable store differs from what the cached registry reflects — a peer instance (or an
     * out-of-band admission) activated/deactivated a TBox — invalidate so the next read
     * rebuilds. A negative interval disables the check (legacy in-process-only behavior).
     */
    private void maybeInvalidateIfStale(String realm) {
        if (stalenessCheckIntervalMs < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (stalenessCheckIntervalMs > 0) {
            Long last = realmLastCheckedAt.get(realm);
            if (last != null && (now - last) < stalenessCheckIntervalMs) {
                return; // within the coherence window — skip the durable-store probe
            }
        }
        try {
            String durableActive = activeHashOrEmpty(realm);
            String reflected = realmActiveHash.getOrDefault(realm, "");
            if (!durableActive.equals(reflected)) {
                Log.infof("Realm %s: active TBox changed (cache reflected '%s', now '%s'); "
                        + "invalidating registry cache for cluster coherence", realm, reflected, durableActive);
                invalidateRealm(realm);
            } else {
                realmLastCheckedAt.put(realm, now);
            }
        } catch (Throwable t) {
            // Best-effort: never fail a read because the coherence probe failed.
            Log.warnf("Realm %s: TBox staleness check failed, serving cached registry: %s",
                    realm, t.getMessage());
        }
    }

    /**
     * Get the ontology registry for a specific DataDomain.
     * <p>
     * First tries to load from TenantOntologyTBox for DataDomain-specific TBoxes.
     * Falls back to realm-level TBox if no DataDomain-specific TBox exists.
     * </p>
     * @param dataDomain the data domain to get registry for
     * @return OntologyRegistry for the DataDomain (or realm if no DataDomain-specific TBox)
     */
    public OntologyRegistry getRegistryForTenant(DataDomain dataDomain) {
        if (dataDomain == null) {
            return getRegistry();
        }
        
        // Create cache key from DataDomain
        String cacheKey = buildDataDomainCacheKey(dataDomain);
        
        return dataDomainRegistries.computeIfAbsent(cacheKey, k -> {
            Log.infof("Loading ontology registry for DataDomain: %s", cacheKey);
            return loadRegistryForDataDomain(dataDomain);
        });
    }
    
    /**
     * Invalidate the cache for a specific DataDomain.
     * @param dataDomain the data domain to invalidate
     */
    public void invalidateTenant(DataDomain dataDomain) {
        if (dataDomain == null) {
            return;
        }
        String cacheKey = buildDataDomainCacheKey(dataDomain);
        dataDomainRegistries.remove(cacheKey);
        Log.infof("Invalidated ontology registry cache for DataDomain: %s", cacheKey);
    }
    
    private String buildDataDomainCacheKey(DataDomain dd) {
        return String.format("%s:%s:%s:%d", 
            dd.getOrgRefName(), dd.getAccountNum(), dd.getTenantId(), dd.getDataSegment());
    }
    
    private OntologyRegistry loadRegistryForDataDomain(DataDomain dataDomain) {
        // Try to load from TenantOntologyTBox first
        try {
            var tenantTBox = tenantTboxRepo.findActiveTBox(dataDomain);
            if (tenantTBox.isPresent()) {
                Log.infof("Loaded TBox for DataDomain %s from TenantOntologyTBox (hash: %s)",
                        buildDataDomainCacheKey(dataDomain), tenantTBox.get().getTboxHash());
                return new PersistedOntologyRegistry(
                    tenantTBox.get().toTBox(),
                    tenantTBox.get().getTboxHash(),
                    tenantTBox.get().getYamlHash(),
                    false
                );
            }
        } catch (Throwable t) {
            Log.warnf("Failed to load TenantOntologyTBox for DataDomain: %s", t.getMessage());
        }

        // During a request, the authenticated realm is the database boundary;
        // tenantId is a data-domain component and must not be treated as a Mongo
        // database name. Standalone callers without a security context retain the
        // historical tenantId fallback.
        String realm = SecurityContext.getPrincipalContext()
                .map(principal -> principal.getDefaultRealm())
                .filter(value -> value != null && !value.isBlank())
                .orElse(dataDomain.getTenantId());
        Log.debugf("No DataDomain-specific TBox found, falling back to realm-level for: %s (realm: %s)",
                buildDataDomainCacheKey(dataDomain), realm);
        return getRegistryForRealm(realm);
    }

    /**
     * Invalidate the cache for a realm.
     * @param realm the realm to invalidate
     */
    public void invalidateRealm(String realm) {
        realmRegistries.remove(realm);
        realmActiveHash.remove(realm);
        realmLastCheckedAt.remove(realm);
        realmRefusals.remove(realm);
        Log.infof("Invalidated ontology registry cache for realm: %s", realm);
    }

    /**
     * Clear all cached registries (both realm and DataDomain).
     */
    public void clearCache() {
        realmRegistries.clear();
        dataDomainRegistries.clear();
        realmActiveHash.clear();
        realmLastCheckedAt.clear();
        realmRefusals.clear();
        Log.info("Cleared all ontology registry caches");
    }

    /**
     * Force rebuild of the TBox for a specific realm.
     * @param realm the realm to rebuild TBox for
     * @return the newly built OntologyRegistry
     */
    public OntologyRegistry forceRebuild(String realm) {
        Log.infof("Force rebuilding TBox for realm: %s", realm);
        realmRefusals.remove(realm);
        OntologyRegistry registry = buildRegistryForRealm(realm);
        realmRegistries.put(realm, registry);
        // Re-stamp so the coherence check tracks this freshly built entry (skipped when
        // coherence checking is disabled, matching getRegistryForRealm).
        if (stalenessCheckIntervalMs >= 0) {
            realmActiveHash.put(realm, activeHashOrEmpty(realm));
            realmLastCheckedAt.put(realm, System.currentTimeMillis());
        }
        return registry;
    }

    /**
     * Build the realm's TBox PURELY from its sources (annotations + Morphia scan +
     * YAML overlay), WITHOUT consulting the activation-preferring read path and
     * WITHOUT mutating the per-realm registry cache.
     * <p>
     * This is the registry the {@code OntologyReindexer} just produced/applied:
     * callers that need to pin/markApplied the rebuilt <em>source</em> TBox (rather
     * than an activation-preferring registry that might return an admission-activated
     * tenant TBox instead) must use this, not {@link #getRegistryForRealm(String)}.
     * When no tenant TBox has been activated, this is identical to what
     * {@code getRegistryForRealm} would have returned, so behaviour is unchanged.
     * </p>
     * @param realm the realm to build the source TBox for
     * @return a freshly built, source-derived OntologyRegistry (not cached)
     */
    public OntologyRegistry buildSourceRegistry(String realm) {
        return buildRegistryForRealm(realm);
    }

    /**
     * Check if a realm has a cached registry.
     */
    public boolean hasCachedRegistry(String realm) {
        return realmRegistries.containsKey(realm);
    }

    /**
     * Get all cached realm names.
     */
    public Set<String> getCachedRealms() {
        return Collections.unmodifiableSet(realmRegistries.keySet());
    }

    // --- Canonical realm -> DataDomain derivation ----------------------------

    /**
     * Canonical realm -&gt; {@link DataDomain} derivation shared by the admission
     * write path ({@code TBoxAdmissionService.dataDomainFor}) and the realm read
     * path ({@link #loadOrBuildRegistryForRealm}). Centralizing it here is what
     * guarantees the tenant-active store is keyed identically on write and read,
     * so activation can never silently fail to be observed because the two sides
     * derived different DataDomains (closes follow-up N3).
     *
     * <p>Principal-independent by contract: derive tenant, organization, account and segment
     * from the registered realm domain, with the realm name as a fixed owner. Admission and read
     * paths share this lookup. A realm absent from the registry keeps the legacy realm-name key;
     * registry outages and failed legacy migrations raise a typed error instead of changing keys.
     * See {@link RealmOntologyKeys}.</p>
     *
     * @param realm the realm; must be non-blank
     * @return the canonical, principal-independent DataDomain for the realm
     */
    public static DataDomain realmDataDomain(String realm) {
        // Principal-independent: derived from the realm's registry entry, never from who is
        // calling, so admission-write and realm-read always agree (RealmOntologyKeys).
        return RealmOntologyKeys.forRealm(realm);
    }

    // --- Private Methods ---

    private String getCurrentRealm() {
        if (SecurityContext.getPrincipalContext().isPresent() && 
            SecurityContext.getResourceContext().isPresent()) {
            return ruleContext.getRealmId(
                SecurityContext.getPrincipalContext().get(),
                SecurityContext.getResourceContext().get()
            );
        }
        return defaultRealm;
    }

    private OntologyRegistry loadOrBuildRegistryForRealm(String realm) {
        // 0. B2 read-path unification: prefer an ACTIVATED TenantOntologyTBox for
        // this realm if one exists. This is strictly additive — behavior changes
        // ONLY when admission has activated a tenant TBox for realmDataDomain(realm);
        // realms with no active tenant TBox fall through to the source build below.
        // realmDataDomain() is the SAME derivation the admission write path uses, so
        // write and read can never drift.
        try {
            Optional<TenantOntologyTBox> activeTenantTBox =
                    tenantTboxRepo.findActiveTBox(realmDataDomain(realm));
            if (activeTenantTBox.isPresent()) {
                TenantOntologyTBox doc = activeTenantTBox.get();
                Log.infof("Realm %s: using ACTIVE TenantOntologyTBox (hash: %s) for read path",
                        realm, doc.getTboxHash());
                return new PersistedOntologyRegistry(
                        doc.toTBox(),
                        doc.getTboxHash(),
                        doc.getYamlHash(),
                        false);
            }
        } catch (RealmOntologyKeys.ResolutionException e) {
            // Unknown registry state or an unfinished re-key must not activate the legacy path.
            throw e;
        } catch (Throwable t) {
            Log.warnf("Failed to load active TenantOntologyTBox for realm %s, "
                    + "falling back to legacy load: %s", realm, t.getMessage());
        }

        // 1. Build from sources. The persisted OntologyTBox is not served in place of the sources: it
        // was keyed on the YAML hash alone, so an annotation or entity change kept serving the old
        // TBox. It is the baseline the stable-relation contract is checked against instead.
        return buildRegistryForRealm(realm);
    }

    private OntologyRegistry buildRegistryForRealm(String realm) {
        Config config = ConfigProvider.getConfig();
        boolean persistTBox = config.getOptionalValue("quantum.ontology.tbox.persist", Boolean.class)
                .orElse(true);
        SourceTBoxGate.Mode stableDiff = SourceTBoxGate.Mode.parse(
                config.getOptionalValue("quantum.ontology.stable-diff", String.class).orElse("enforce"));

        TBox accumulated = new TBox(Map.of(), Map.of(), List.of());
        TBox pack = null;
        Optional<Integer> packVersion = Optional.empty();
        List<String> sourceFailures = new ArrayList<>();

        // 1. Scan configured packages for @OntologyClass/@OntologyProperty annotations
        try {
            Optional<String> scan = config.getOptionalValue("quantum.ontology.scan.packages", String.class);
            if (scan.isPresent() && !scan.get().isBlank()) {
                List<String> pkgs = Arrays.stream(scan.get().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!pkgs.isEmpty()) {
                    AnnotationOntologyLoader annoLoader = new AnnotationOntologyLoader();
                    TBox annoTBox = annoLoader.loadFromPackages(pkgs);
                    // Close cross-module class references (e.g. a property whose range is a class
                    // OWNED BY ANOTHER pack) to empty external stubs, so a single federated edge does
                    // not make the strict validator reject — and thereby discard — every real class
                    // this module declares. See OntologyReferenceCloser.
                    annoTBox = OntologyReferenceCloser.closeClassReferences(annoTBox);
                    accumulated = OntologyMerger.merge(accumulated, annoTBox);
                    Log.debugf("Realm %s: Loaded %d classes from annotations", realm, annoTBox.classes().size());
                }
            }
        } catch (Throwable t) {
            Log.warnf("Failed to load annotations for realm %s: %s", realm, t.getMessage());
            sourceFailures.add("annotations: " + t.getMessage());
        }

        // 2. Merge in Morphia-derived TBox from realm-specific datastore
        try {
            MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            if (ds != null && ds.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(ds);
                TBox base = loader.loadTBox();
                base = OntologyReferenceCloser.closeClassReferences(base);
                accumulated = OntologyMerger.merge(accumulated, base);
                Log.debugf("Realm %s: Loaded %d classes from Morphia", realm, base.classes().size());
            }
        } catch (Throwable t) {
            Log.warnf("Failed to load Morphia TBox for realm %s: %s", realm, t.getMessage());
            sourceFailures.add("Morphia entities: " + t.getMessage());
        }

        // 3. YAML overlay (can be realm-specific or global)
        Optional<Path> yamlPath = Optional.empty();
        String currentHash = "unknown";
        boolean needsReindex = false;
        try {
            YamlOntologyLoader yaml = new YamlOntologyLoader();
            yamlPath = resolveYamlPath(realm);
            YamlOntologyLoader.Pack loaded = null;
            if (yamlPath.isPresent() && Files.exists(yamlPath.get())) {
                loaded = yaml.loadPackFromPath(yamlPath.get());
            } else {
                if (YamlOntologyLoader.class.getResource("/ontology.yaml") != null) {
                    loaded = yaml.loadPackFromClasspath("/ontology.yaml");
                }
            }
            if (loaded != null) {
                pack = loaded.tbox();
                packVersion = loaded.version();
                TBox overlay = OntologyReferenceCloser.closeClassReferences(pack);
                accumulated = OntologyMerger.merge(accumulated, overlay);
            }
            var result = metaService.observeYaml(yamlPath, "/ontology.yaml");
            currentHash = result.currentHash();
            needsReindex = metaService.getMeta().map(m -> m.isReindexRequired()).orElse(false);
        } catch (Throwable t) {
            Log.warnf("Failed to load YAML overlay for realm %s: %s", realm, t.getMessage());
            sourceFailures.add("YAML pack: " + t.getMessage());
        }

        // 4. Validate final TBox
        try {
            OntologyValidator.validate(accumulated);
        } catch (Exception e) {
            Log.warnf("TBox validation failed for realm %s: %s", realm, e.getMessage());
        }

        List<String> outsidePack = SourceTBoxGate.stableOutsidePack(accumulated, pack);
        if (!outsidePack.isEmpty()) {
            Log.warnf("Realm %s: relations %s are stable only through annotations; declare them stable "
                    + "in the ontology pack YAML, where the contract is versioned", realm, outsidePack);
        }

        // 5. Stable-relation contract against the last TBox built for this realm, then persist.
        // A breaking change refuses the build (enforce) before it is persisted, so the next build is
        // checked against the same baseline.
        if (!persistTBox) {
            Log.debugf("Realm %s: quantum.ontology.tbox.persist=false, so stable relations are not checked "
                    + "against a baseline", realm);
        } else {
            TBox candidate = accumulated;
            TBox packTBox = pack;
            Integer declaredVersion = packVersion.orElse(null);
            int candidateVersion = declaredVersion != null ? declaredVersion : 0;
            String yamlHash = currentHash;
            Optional<Path> source = yamlPath;
            inSystemScope(realm, () -> {
                String builder = tboxBuilder(config);
                checkStableContract(realm, builder, candidate, candidateVersion, packTBox, sourceFailures, stableDiff);
                doPersistBuiltTBox(realm, builder, candidate, yamlHash, source, declaredVersion);
            });
        }

        Log.infof("Built ontology registry for realm %s: %d classes, %d properties",
                realm, accumulated.classes().size(), accumulated.properties().size());

        return new YamlBackedOntologyRegistry(accumulated, currentHash, needsReindex);
    }

    /**
     * Run {@code work} (the stable-relation check and the persist of the freshly built
     * <em>source</em> TBox) under a realm-scoped SYSTEM security context.
     * <p>
     * The Morphia read and save paths resolve the target realm (and, via the audit interceptor, the
     * acting identity) from the ambient {@link SecurityContext}, falling back to the
     * request-scoped {@link io.quarkus.security.identity.SecurityIdentity} proxy when no
     * context is set. That fallback is fatal when {@code buildRegistryForRealm} runs OUTSIDE a
     * request/security context — e.g. the {@code OntologyStartupInitializer} daemon thread that
     * publishes the TBox at boot ({@code "ontology-tbox-publisher"}), or a {@code @PostConstruct}
     * startup path: dereferencing the proxy throws "RequestScoped context was not active …".
     * The scope lets the realm resolve WITHOUT touching the request-scoped proxy.
     * <p>
     * The persist is best-effort ({@link #doPersistBuiltTBox}); a stable-relation breach is not.
     */
    private void inSystemScope(String realm, Runnable work) {
        // Persisting the source-derived TBox is a SYSTEM cache operation (annotations + YAML), not a
        // user-authored write. It can be reached three ways, and ALL of them must resolve to a
        // realm-scoped SYSTEM write context — never the ambient request context:
        //   1. A no-context startup/daemon thread (e.g. "ontology-tbox-publisher"): the repo would
        //      otherwise dereference the request-scoped SecurityIdentity proxy and throw.
        //   2. A READ request (GET /tbox) that misses the active tenant TBox and falls through to the
        //      source rebuild: the ambient ResourceContext carries action=VIEW/NONE, so the
        //      persistence guard rejects the save with "Persistence callback requires an explicit
        //      write action; received: none" — the persist is skipped and every later read re-scans.
        //   3. An admission write: already carries a write context; re-scoping it is harmless.
        // realmDataDomain(realm) is the SAME principal-independent key the read/admission paths use,
        // so the save lands in the correct realm database. Rules are bypassed because there is no
        // authenticated user authorizing THIS write — the system is caching its OWN source-derived
        // TBox — and a synthetic SYSTEM principal holds no per-realm ONTOLOGY/TBOX grant. Only
        // genuine internal rebuild/startup paths reach here; external writes go through admission,
        // which keeps full permission enforcement. The baseline read for the stable-relation
        // contract runs in the same scope.
        PrincipalContext principal = SecurityCallScope.service(
                realm, realmDataDomain(realm), "ontology-tbox-publisher");
        ResourceContext resource = SecurityCallScope.writeResource(principal, realm, "ONTOLOGY", "TBOX");
        try (SecurityCallScope.Scope ctx = SecurityCallScope.open(principal, resource);
             SecurityCallScope.Scope ignoreRules = SecurityCallScope.openIgnoringRules()) {
            work.run();
        }
    }

    /**
     * The application that builds this TBox from its sources: {@code quantum.ontology.tbox.builder},
     * else {@code quarkus.application.name}. Each builder is checked against its own last build, so
     * applications sharing a realm database do not block each other.
     */
    private static String tboxBuilder(Config config) {
        return config.getOptionalValue("quantum.ontology.tbox.builder", String.class)
                .or(() -> config.getOptionalValue("quarkus.application.name", String.class))
                .filter(b -> !b.isBlank())
                .orElse("default");
    }

    /**
     * Diff the source TBox against the last one {@code builder} built for the realm
     * ({@link SourceTBoxGate}). In enforce mode a breaking change, or a baseline that cannot be
     * read, refuses the build: the contract cannot be shown to hold. In warn mode both are logged
     * as errors. A TBox the builder has built before (a rollback) is admitted with a warning.
     */
    private void checkStableContract(String realm, String builder, TBox candidate, int candidateVersion, TBox pack,
                                     List<String> sourceFailures, SourceTBoxGate.Mode mode) {
        Optional<OntologyTBox> baseline;
        boolean builtBefore;
        try {
            baseline = tboxRepo.findLatestForBuilder(builder);
            builtBefore = baseline.isPresent()
                    && tboxRepo.findByHashAndBuilder(TBoxHasher.computeHash(candidate), builder).isPresent();
        } catch (Throwable t) {
            SourceTBoxGate.BaselineUnreadableException unreadable =
                    new SourceTBoxGate.BaselineUnreadableException(realm, t);
            if (mode == SourceTBoxGate.Mode.ENFORCE) throw unreadable;
            Log.error(unreadable.getMessage().replace("=enforce", "=warn"));
            return;
        }
        if (baseline.isEmpty()) return;
        Integer baselineVersion = baseline.get().getVersion();
        SourceTBoxGate.Outcome outcome = SourceTBoxGate.check(
                baseline.get().toTBox(), baselineVersion, candidate, candidateVersion, pack);
        if (outcome.violations().isEmpty()) return;
        SourceTBoxGate.StableContractException breach = new SourceTBoxGate.StableContractException(
                realm, outcome.violations(), baselineVersion, candidateVersion, sourceFailures);
        if (builtBefore && !outcome.admissible()) {
            Log.warnf("Realm %s: %s rebuilt a TBox it built before (a rollback?), which changes stable relations "
                    + "of its last build: %s", realm, builder, outcome.violations().stream()
                            .map(v -> v.code().wireValue() + " " + v.relation()).toList());
        } else if (outcome.admissible()) {
            Log.infof("Realm %s: pack version %d (last built %s) publishes stable-relation changes: %s",
                    realm, candidateVersion, baselineVersion, outcome.violations().stream()
                            .map(v -> v.code().wireValue() + " " + v.relation()).toList());
        } else if (mode == SourceTBoxGate.Mode.ENFORCE) {
            throw breach;
        } else {
            Log.error(breach.getMessage() + " (quantum.ontology.stable-diff=warn)");
        }
    }

    /**
     * The actual persist: idempotent by canonical TBox hash. Assumes an active security context
     * (see {@link #inSystemScope}). Best-effort by contract: a persistence failure is logged and
     * never breaks a read or startup. A TBox built again is re-stamped, so the latest one stays the
     * baseline of the next build.
     */
    private void doPersistBuiltTBox(String realm, String builder, TBox accumulated, String currentHash,
                                    Optional<Path> yamlPath, Integer packVersion) {
        try {
            String tboxHash = TBoxHasher.computeHash(accumulated);
            String source = yamlPath.map(Path::toString).orElse("/ontology.yaml");

            Optional<OntologyTBox> existing = tboxRepo.findByHashAndBuilder(tboxHash, builder);
            if (existing.isEmpty()) {
                OntologyTBox newTBox = new OntologyTBox(accumulated, tboxHash, currentHash, source);
                newTBox.setVersion(packVersion);
                newTBox.setBuilder(builder);
                tboxRepo.save(newTBox);
                Log.infof("Persisted new TBox for realm %s (hash: %s, yamlHash: %s)",
                        realm, tboxHash, currentHash);
            } else {
                OntologyTBox doc = existing.get();
                doc.setAppliedAt(new Date());
                // An unversioned rebuild keeps the version the TBox was recorded with.
                if (packVersion != null) doc.setVersion(packVersion);
                tboxRepo.save(doc);
                Log.debugf("TBox already persisted for realm %s (hash: %s); re-stamped", realm, tboxHash);
            }
        } catch (Throwable t) {
            Log.warnf("Failed to persist TBox for realm %s: %s", realm, t.getMessage());
        }
    }

    /**
     * Resolve YAML path, supporting realm-specific overrides.
     * Checks: ontology-{realm}.yaml first, then ontology.yaml
     */
    private Optional<Path> resolveYamlPath(String realm) {
        // 1. System property
        String sys = System.getProperty("ontology.yaml.path");
        if (sys != null && !sys.isBlank()) return Optional.of(Path.of(sys));

        // 2. Environment variable
        String env = System.getenv("ONTOLOGY_YAML");
        if (env != null && !env.isBlank()) return Optional.of(Path.of(env));

        // 3. Realm-specific conventional location
        Path realmSpecific = Path.of("config", "ontology-" + realm + ".yaml");
        if (Files.exists(realmSpecific)) return Optional.of(realmSpecific);

        // 4. Global conventional location
        Path conventional = Path.of("config", "ontology.yaml");
        if (Files.exists(conventional)) return Optional.of(conventional);

        return Optional.empty();
    }

}
