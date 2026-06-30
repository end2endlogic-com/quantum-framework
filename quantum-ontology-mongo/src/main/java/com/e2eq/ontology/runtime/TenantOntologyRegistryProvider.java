package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.PrincipalContext;
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
 * <p>
 * This provider supports true multi-tenancy by:
 * <ul>
 *   <li>Caching OntologyRegistry instances per realm (MongoDB database)</li>
 *   <li>Rebuilding TBox from Morphia scan + YAML when no persisted TBox exists</li>
 *   <li>Persisting rebuilt TBox in realm-specific collections</li>
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

    // Cache of ontology registries per realm
    private final Map<String, OntologyRegistry> realmRegistries = new ConcurrentHashMap<>();
    
    // Cache of ontology registries per DataDomain (for DataDomain-specific TBoxes)
    private final Map<String, OntologyRegistry> dataDomainRegistries = new ConcurrentHashMap<>();

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
     * B5 vocabulary tier: the set of ontology predicates ADMITTED for policy
     * (security-rule) use in the current security-context realm.
     * <p>
     * Resolves the realm the SAME way {@link #getRegistry()} does (via the current
     * SecurityContext, falling back to {@code defaultRealm}), then reads the realm's
     * ACTIVE {@link TenantOntologyTBox} fresh (NOT cached — admission state changes
     * out-of-band). Returns:
     * <ul>
     *   <li>{@code Optional.of(set)} when an active tenant TBox exists and its
     *       {@code admittedPredicates} field is non-null (governed realm); the set
     *       defines exactly which declared predicates may be referenced by rules.</li>
     *   <li>{@code Optional.empty()} otherwise — no active tenant doc, or a legacy
     *       doc with a null {@code admittedPredicates} field — meaning "all declared
     *       predicates admitted" (back-compat).</li>
     * </ul>
     * Read fresh on every call and fully null-safe.
     * </p>
     * @return the admitted-predicate set for the current realm, or empty for legacy
     */
    public java.util.Optional<java.util.Set<String>> admittedPredicatesForCurrentRealm() {
        String realm = getCurrentRealm();
        try {
            Optional<TenantOntologyTBox> activeTenantTBox =
                    tenantTboxRepo.findActiveTBox(realmDataDomain(realm));
            if (activeTenantTBox.isPresent()) {
                java.util.Set<String> admitted = activeTenantTBox.get().getAdmittedPredicates();
                if (admitted != null) {
                    return java.util.Optional.of(admitted);
                }
            }
        } catch (Throwable t) {
            Log.debugf("admittedPredicatesForCurrentRealm: could not resolve active tenant TBox for "
                    + "realm %s, treating as legacy (all admitted): %s", realm, t.getMessage());
        }
        return java.util.Optional.empty();
    }

    /**
     * Get the ontology registry for a specific realm.
     * <p>
     * If no cached registry exists, attempts to load from persisted TBox.
     * If no persisted TBox exists, rebuilds from Morphia scan + YAML overlay.
     * </p>
     * @param realm the realm (database name) to get registry for
     * @return OntologyRegistry for the realm
     */
    public OntologyRegistry getRegistryForRealm(String realm) {
        return realmRegistries.computeIfAbsent(realm, r -> {
            Log.infof("Loading ontology registry for realm: %s", r);
            return loadOrBuildRegistryForRealm(r);
        });
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

        // Fall back to realm-level TBox using tenantId from the DataDomain
        // (not getCurrentRealm() which depends on SecurityContext)
        String realm = dataDomain.getTenantId();
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
        Log.infof("Invalidated ontology registry cache for realm: %s", realm);
    }

    /**
     * Clear all cached registries (both realm and DataDomain).
     */
    public void clearCache() {
        realmRegistries.clear();
        dataDomainRegistries.clear();
        Log.info("Cleared all ontology registry caches");
    }

    /**
     * Force rebuild of the TBox for a specific realm.
     * @param realm the realm to rebuild TBox for
     * @return the newly built OntologyRegistry
     */
    public OntologyRegistry forceRebuild(String realm) {
        Log.infof("Force rebuilding TBox for realm: %s", realm);
        OntologyRegistry registry = buildRegistryForRealm(realm);
        realmRegistries.put(realm, registry);
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
     * <p><b>Principal-independent by contract.</b> This function is a PURE
     * function of the {@code realm} string: it makes NO reference to the ambient
     * {@link SecurityContext}/{@link PrincipalContext}. For a given realm it
     * returns byte-identical org/account/tenantId/owner/dataSegment regardless of
     * which caller (HTTP request under a JWT principal, the
     * {@code OntologyReindexer} system principal, or no principal at all) invokes
     * it. The fixed per-realm convention is:
     * {@code tenantId == org == account == owner == realm} and
     * {@code dataSegment == 0}.</p>
     *
     * <p>This is the single realm-deterministic key used by BOTH the admission
     * write ({@code TBoxAdmissionService.dataDomainFor}) and the realm read
     * ({@code findActiveTBox(realmDataDomain(realm))}). Because every caller now
     * computes the identical key for a realm, the per-realm registry cache
     * ({@code realmRegistries.computeIfAbsent(realm, ...)}) is also correct:
     * whichever thread warms the cache first derives the same lookup key, so the
     * first-warmer-wins behaviour can no longer pin a principal-dependent key.</p>
     *
     * <p>The tenant-active lookup ({@code TenantOntologyTBoxRepo.findActiveTBox})
     * keys only on org/account/tenantId/dataSegment (not owner), all of which are
     * constants of {@code realm} here, so write == read is deterministic per realm.
     * Admission may additionally layer an explicit request-body DataDomain on top
     * of this base, but it must not diverge from this key for {realm}-scoped
     * endpoints (enforced in {@code TBoxAdmissionService.dataDomainFor}).</p>
     *
     * @param realm the realm (== tenantId); must be non-blank
     * @return the canonical, principal-independent DataDomain for the realm
     */
    public static DataDomain realmDataDomain(String realm) {
        // PURE function of realm: do NOT read SecurityContext/PrincipalContext here.
        // The lookup key MUST be identical for the same realm across every caller
        // (HTTP-under-JWT, OntologyReindexer system principal, or no principal),
        // otherwise admission-write and realm-read can drift onto different keys
        // and an activation is silently missed (the multi-principal blocker).
        String tenantId = realm;
        String org = realm;
        String account = realm;
        String owner = realm;
        int segment = 0;
        return new DataDomain(org, account, tenantId, segment, owner);
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
        Config config = ConfigProvider.getConfig();
        boolean persistTBox = config.getOptionalValue("quantum.ontology.tbox.persist", Boolean.class)
                .orElse(true);
        boolean forceRebuild = config.getOptionalValue("quantum.ontology.tbox.force-rebuild", Boolean.class)
                .orElse(false);

        // 0. B2 read-path unification: prefer an ACTIVATED TenantOntologyTBox for
        // this realm if one exists. This is strictly additive — behavior changes
        // ONLY when admission has activated a tenant TBox for realmDataDomain(realm);
        // realms with no active tenant TBox fall through UNCHANGED to the legacy
        // findLatest()/build path below. realmDataDomain() is the SAME derivation
        // the admission write path uses, so write and read can never drift.
        //
        // This is intentionally NOT gated by force-rebuild: force-rebuild governs
        // rebuilding the legacy/source-derived TBox, whereas an activated tenant
        // TBox is an explicit admission decision that must be observed on reads.
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
        } catch (Throwable t) {
            Log.warnf("Failed to load active TenantOntologyTBox for realm %s, "
                    + "falling back to legacy load: %s", realm, t.getMessage());
        }

        // 1. Try to load from persisted TBox first (if persistence is enabled)
        if (persistTBox && !forceRebuild) {
            try {
                Optional<OntologyTBox> persistedTBox = loadPersistedTBox(realm);
                if (persistedTBox.isPresent()) {
                    // Check if YAML has changed
                    Optional<Path> yamlPath = resolveYamlPath(realm);
                    var yamlResult = metaService.observeYaml(yamlPath, "/ontology.yaml");
                    String currentYamlHash = yamlResult.currentHash();
                    String persistedYamlHash = persistedTBox.get().getYamlHash();
                    
                    // If YAML hash matches, use persisted TBox
                    if (persistedYamlHash != null && persistedYamlHash.equals(currentYamlHash)) {
                        boolean needsReindex = metaService.getMeta()
                                .map(m -> m.isReindexRequired())
                                .orElse(false);
                        Log.infof("Loaded TBox for realm %s from persistence (hash: %s, yamlHash: %s)", 
                                realm, persistedTBox.get().getTboxHash(), persistedYamlHash);
                        return new PersistedOntologyRegistry(
                            persistedTBox.get().toTBox(),
                            persistedTBox.get().getTboxHash(),
                            persistedTBox.get().getYamlHash(),
                            needsReindex
                        );
                    } else {
                        Log.infof("YAML changed for realm %s, rebuilding TBox (old: %s, new: %s)", 
                                realm, persistedYamlHash, currentYamlHash);
                    }
                }
            } catch (Throwable t) {
                Log.warnf("Failed to load persisted TBox for realm %s, will rebuild: %s", realm, t.getMessage());
            }
        }

        // 2. Build from sources
        return buildRegistryForRealm(realm);
    }

    private Optional<OntologyTBox> loadPersistedTBox(String realm) {
        // Use the realm-specific datastore for querying
        // The repos now use getSecurityContextRealmId() which should return the correct realm
        return tboxRepo.findLatest();
    }

    private OntologyRegistry buildRegistryForRealm(String realm) {
        Config config = ConfigProvider.getConfig();
        boolean persistTBox = config.getOptionalValue("quantum.ontology.tbox.persist", Boolean.class)
                .orElse(true);

        TBox accumulated = new TBox(Map.of(), Map.of(), List.of());

        // 1. Scan configured packages for @OntologyClass/@OntologyProperty annotations
        try {
            Optional<String> scan = config.getOptionalValue("quantum.ontology.scan.packages", String.class);
            if (scan.isPresent() && !scan.get().isBlank()) {
                List<String> pkgs = Arrays.stream(scan.get().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!pkgs.isEmpty()) {
                    AnnotationOntologyLoader annoLoader = new AnnotationOntologyLoader();
                    TBox annoTBox = annoLoader.loadFromPackages(pkgs);
                    accumulated = OntologyMerger.merge(accumulated, annoTBox);
                    Log.debugf("Realm %s: Loaded %d classes from annotations", realm, annoTBox.classes().size());
                }
            }
        } catch (Throwable t) {
            Log.warnf("Failed to load annotations for realm %s: %s", realm, t.getMessage());
        }

        // 2. Merge in Morphia-derived TBox from realm-specific datastore
        try {
            MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            if (ds != null && ds.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(ds);
                TBox base = loader.loadTBox();
                accumulated = OntologyMerger.merge(accumulated, base);
                Log.debugf("Realm %s: Loaded %d classes from Morphia", realm, base.classes().size());
            }
        } catch (Throwable t) {
            Log.warnf("Failed to load Morphia TBox for realm %s: %s", realm, t.getMessage());
        }

        // 3. YAML overlay (can be realm-specific or global)
        Optional<Path> yamlPath = Optional.empty();
        String currentHash = "unknown";
        boolean needsReindex = false;
        try {
            YamlOntologyLoader yaml = new YamlOntologyLoader();
            yamlPath = resolveYamlPath(realm);
            TBox overlay = null;
            if (yamlPath.isPresent() && Files.exists(yamlPath.get())) {
                overlay = yaml.loadFromPath(yamlPath.get());
            } else {
                try { overlay = yaml.loadFromClasspath("/ontology.yaml"); } catch (Exception ignored) { }
            }
            if (overlay != null) {
                accumulated = OntologyMerger.merge(accumulated, overlay);
            }
            var result = metaService.observeYaml(yamlPath, "/ontology.yaml");
            currentHash = result.currentHash();
            needsReindex = metaService.getMeta().map(m -> m.isReindexRequired()).orElse(false);
        } catch (Throwable t) {
            Log.warnf("Failed to load YAML overlay for realm %s: %s", realm, t.getMessage());
        }

        // 4. Validate final TBox
        try { 
            OntologyValidator.validate(accumulated); 
        } catch (Exception e) { 
            Log.warnf("TBox validation failed for realm %s: %s", realm, e.getMessage());
        }

        // 5. Persist TBox if persistence is enabled
        if (persistTBox) {
            try {
                String tboxHash = TBoxHasher.computeHash(accumulated);
                String source = yamlPath.map(Path::toString).orElse("/ontology.yaml");
                
                // Check if this TBox already exists
                Optional<OntologyTBox> existing = tboxRepo.findByHash(tboxHash);
                if (existing.isEmpty()) {
                    OntologyTBox newTBox = new OntologyTBox(accumulated, tboxHash, currentHash, source);
                    tboxRepo.save(newTBox);
                    Log.infof("Persisted new TBox for realm %s (hash: %s, yamlHash: %s)", 
                            realm, tboxHash, currentHash);
                } else {
                    Log.debugf("TBox already persisted for realm %s (hash: %s)", realm, tboxHash);
                }
            } catch (Throwable t) {
                Log.warnf("Failed to persist TBox for realm %s: %s", realm, t.getMessage());
            }
        }

        Log.infof("Built ontology registry for realm %s: %d classes, %d properties", 
                realm, accumulated.classes().size(), accumulated.properties().size());

        return new YamlBackedOntologyRegistry(accumulated, currentHash, needsReindex);
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