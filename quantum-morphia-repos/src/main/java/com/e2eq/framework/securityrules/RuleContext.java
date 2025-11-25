package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.IOCase;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.WildCardMatcher;
import com.google.common.collect.Ordering;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;


import java.util.*;


@ApplicationScoped
public class RuleContext {

    // Thread-local to propagate EvalMode into the core rule evaluation loop without changing broad method signatures
    private static final ThreadLocal<com.e2eq.framework.model.securityrules.EvalMode> TL_EVAL_MODE = new ThreadLocal<>();

    private static com.e2eq.framework.model.securityrules.EvalMode getEvalModeForThread() {
        com.e2eq.framework.model.securityrules.EvalMode m = TL_EVAL_MODE.get();
        return (m != null) ? m : com.e2eq.framework.model.securityrules.EvalMode.LEGACY;
    }

    private static void setEvalModeForThread(com.e2eq.framework.model.securityrules.EvalMode m) {
        if (m == null) TL_EVAL_MODE.remove(); else TL_EVAL_MODE.set(m);
    }

    private static void clearEvalModeForThread() {
        TL_EVAL_MODE.remove();
    }

    @Inject
    Instance<AccessListResolver> resolvers;
     @Inject
     SecurityUtils securityUtils;

     @Inject
     EnvConfigUtils envConfigUtils;

     @Inject
     PolicyRepo policyRepo;

    /**
     * This holds a map of rules, indexed by an "identity" where an identity may be either
     * a specific userId, or a role name.
     */
    Map<String, List<Rule>> rules = new HashMap<>();

    // Optional compiled discrimination index (off by default)
    @ConfigProperty(name = "quantum.security.rules.index.enabled", defaultValue = "false")
    boolean indexEnabled;
    private volatile RuleIndex compiledIndex;

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "system-com")
    protected String defaultRealm;

    // Scripting controls (sandbox & timeout)
    @ConfigProperty(name = "quantum.security.scripting.enabled", defaultValue = "true")
    boolean scriptingEnabled;

    @ConfigProperty(name = "quantum.security.scripting.timeout.millis", defaultValue = "250")
    long scriptingTimeoutMillis;

    @ConfigProperty(name = "quantum.security.scripting.allowAllAccess", defaultValue = "false")
    boolean scriptingAllowAllAccess;

    // Policy for handling rules with filters when no concrete resource is provided on single-resource checks
    // Values: DEFER (legacy-compatible, default), CONSERVATIVE_NA (strict)
    @ConfigProperty(name = "security.rules.noResourceFilterPolicy", defaultValue = "DEFER")
    String noResourceFilterPolicy;

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_PERMISSIVE = new java.util.concurrent.atomic.AtomicBoolean(false);

    public RuleContext(SecurityUtils securityUtils, EnvConfigUtils envConfigUtils) {
        this.securityUtils = securityUtils;
        this.envConfigUtils = envConfigUtils;
    }

    public RuleContext() {
        // load rules
        //TODO: Need to understand how to determine tenant, account etc on initialization
        Log.debug("Creating ruleContext");
    }

    private String getNoResourceFilterPolicy() {
        try {
            return (noResourceFilterPolicy != null && !noResourceFilterPolicy.isBlank())
                    ? noResourceFilterPolicy
                    : "DEFER";
        } catch (Throwable t) {
            return "DEFER";
        }
    }


    public String getDefaultRealm() {
        return defaultRealm;
    }

    /**
     * Export a snapshot of the current rule index and rule set for client-side evaluation.
     * When the index is disabled or unavailable, enabled=false will be set and version=0.
     */
    public com.e2eq.framework.model.securityrules.RuleIndexSnapshot exportIndexSnapshot() {
        // Full snapshot of all identities
        return exportIndexSnapshotForIdentities(this.rules.keySet());
    }

    /**
     * Export a snapshot of the current rule index, limited to the provided identities.
     * Identities are keys in the in-memory rules map (userId or role names).
     */
    public com.e2eq.framework.model.securityrules.RuleIndexSnapshot exportIndexSnapshotForIdentities(java.util.Collection<String> identities) {
        com.e2eq.framework.model.securityrules.RuleIndexSnapshot snap = new com.e2eq.framework.model.securityrules.RuleIndexSnapshot();
        boolean enabled = indexEnabled && compiledIndex != null;
        snap.setEnabled(enabled);
        snap.setVersion(enabled ? compiledIndex.getVersion() : 0L);
        snap.setPolicyVersion(getPolicyVersion());
        if (identities != null) {
            for (String id : identities) {
                java.util.List<Rule> list = rules.get(id);
                if (list == null) continue;
                for (Rule r : list) {
                    snap.getRules().add(com.e2eq.framework.model.securityrules.RuleIndexSnapshot.fromRule(r));
                }
            }
        }
        // Provide overall stable ordering by priority then name to help clients
        snap.getRules().sort((a, b) -> {
            int c = Integer.compare(a.getPriority(), b.getPriority());
            if (c != 0) return c;
            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            return an.compareToIgnoreCase(bn);
        });
        return snap;
    }

    /**
     * Export a scoped access matrix for the provided identities and optional requested data domain.
     * This materializes server-side precedence into a client-usable structure.
     */
    public com.e2eq.framework.model.securityrules.RuleIndexSnapshot exportScopedAccessMatrixForIdentities(java.util.Collection<String> identities, com.e2eq.framework.model.persistent.base.DataDomain requested) {
        com.e2eq.framework.model.securityrules.RuleIndexSnapshot snap = new com.e2eq.framework.model.securityrules.RuleIndexSnapshot();
        boolean enabled = indexEnabled && compiledIndex != null;
        snap.setEnabled(enabled);
        snap.setVersion(enabled ? compiledIndex.getVersion() : 0L);
        snap.setPolicyVersion(getPolicyVersion());
        if (identities != null) snap.getSources().addAll(identities);

        // Bucket rules by scope key derived from body
        java.util.Map<String, java.util.List<Rule>> byScope = new java.util.HashMap<>();
        if (identities != null) {
            for (String id : identities) {
                java.util.List<Rule> list = rules.get(id);
                if (list == null) continue;
                for (Rule r : list) {
                    if (r == null || r.getSecurityURI() == null) continue;
                    com.e2eq.framework.model.securityrules.SecurityURI suri = r.getSecurityURI();
                    com.e2eq.framework.model.securityrules.SecurityURIBody body = suri.getBody();
                    com.e2eq.framework.model.securityrules.SecurityURIHeader header = suri.getHeader();
                    if (header == null) continue;
                    // Skip rules that require runtime scripts
                    if (hasDynamic(r)) {
                        snap.setRequiresServer(true);
                        // don't add to materialized buckets
                        continue;
                    }
                    String scopeKey = toScopeKey(body);
                    if (scopeKey == null) scopeKey = globalScopeKey();
                    byScope.computeIfAbsent(scopeKey, k -> new java.util.ArrayList<>()).add(r);
                }
            }
        }

        // For each scope, compute matrix winners
        for (java.util.Map.Entry<String, java.util.List<Rule>> e : byScope.entrySet()) {
            com.e2eq.framework.model.securityrules.RuleIndexSnapshot.ScopedMatrix sm = new com.e2eq.framework.model.securityrules.RuleIndexSnapshot.ScopedMatrix();
            materializeHeaderOutcomes(sm.getMatrix(), e.getValue());
            snap.getScopes().put(e.getKey(), sm);
        }

        // requested scope + fallback
        if (requested != null) {
            String reqKey = toExactOrStarScopeKey(requested);
            snap.setRequestedScope(reqKey);
            snap.setRequestedFallback(buildFallbackChain(reqKey));
        }

        // Preserve legacy rules list (for compatibility)
        if (identities != null) {
            for (String id : identities) {
                java.util.List<Rule> list = rules.get(id);
                if (list == null) continue;
                for (Rule r : list) {
                    snap.getRules().add(com.e2eq.framework.model.securityrules.RuleIndexSnapshot.fromRule(r));
                }
            }
            snap.getRules().sort((a, b) -> {
                int c = Integer.compare(a.getPriority(), b.getPriority());
                if (c != 0) return c;
                String an = a.getName() == null ? "" : a.getName();
                String bn = b.getName() == null ? "" : b.getName();
                return an.compareToIgnoreCase(bn);
            });
        }

        return snap;
    }

    private static boolean hasDynamic(Rule r) {
        try {
            // Prefer getPostconditionScript if present; fall back to getScript if exists
            java.lang.reflect.Method m;
            m = r.getClass().getMethod("getPostconditionScript");
            Object v = m.invoke(r);
            if (v != null && v.toString().trim().length() > 0) return true;
        } catch (Exception ignore) { }
        try {
            java.lang.reflect.Method m2 = r.getClass().getMethod("getScript");
            Object v2 = m2.invoke(r);
            if (v2 != null && v2.toString().trim().length() > 0) return true;
        } catch (Exception ignore) { }
        return false;
    }

    private static String toScopeKey(com.e2eq.framework.model.securityrules.SecurityURIBody body) {
        if (body == null) return globalScopeKey();
        String org = val(body.getOrgRefName());
        String acct = val(body.getAccountNumber());
        String tenant = val(body.getTenantId());
        String seg = val(body.getDataSegment());
        String owner = val(body.getOwnerId());
        return String.format("org=%s|acct=%s|tenant=%s|seg=%s|owner=%s", org, acct, tenant, seg, owner);
    }

    private static String toExactOrStarScopeKey(com.e2eq.framework.model.persistent.base.DataDomain dd) {
        String org = val(dd.getOrgRefName());
        String acct = val(dd.getAccountNum());
        String tenant = val(dd.getTenantId());
        String seg = val(dd.getDataSegment());
        String owner = val(dd.getOwnerId());
        return String.format("org=%s|acct=%s|tenant=%s|seg=%s|owner=%s", org, acct, tenant, seg, owner);
    }

    private static String val(Object o) {
        if (o == null) return "*";
        String s = String.valueOf(o);
        if (s.trim().isEmpty()) return "*";
        return s;
    }

    private static String globalScopeKey() { return "org=*|acct=*|tenant=*|seg=*|owner=*"; }

    private static java.util.List<String> buildFallbackChain(String startKey) {
        java.util.List<String> chain = new java.util.ArrayList<>();
        java.util.Map<String,String> p = parseScopeKey(startKey);
        if (p == null) return chain;
        String[][] steps = new String[][] {
                {"owner","*"}, {"seg","*"}, {"tenant","*"}, {"acct","*"}, {"org","*"}
        };
        java.util.Map<String,String> cur = new java.util.HashMap<>(p);
        for (String[] st : steps) {
            cur.put(st[0], st[1]);
            chain.add(formatScopeKey(cur));
        }
        String g = globalScopeKey();
        if (chain.isEmpty() || !chain.get(chain.size()-1).equals(g)) chain.add(g);
        return chain;
    }

    private static java.util.Map<String,String> parseScopeKey(String key) {
        if (key == null) return null;
        String[] parts = key.split("\\|");
        java.util.Map<String,String> map = new java.util.HashMap<>();
        for (String p : parts) {
            String[] kv = p.split("=");
            if (kv.length != 2) return null;
            map.put(kv[0], kv[1]);
        }
        if (!map.containsKey("org") || !map.containsKey("acct") || !map.containsKey("tenant") || !map.containsKey("seg") || !map.containsKey("owner")) return null;
        return map;
    }

    private static String formatScopeKey(java.util.Map<String,String> parts) {
        return String.format("org=%s|acct=%s|tenant=%s|seg=%s|owner=%s", parts.get("org"), parts.get("acct"), parts.get("tenant"), parts.get("seg"), parts.get("owner"));
    }

    private static void materializeHeaderOutcomes(java.util.Map<String, java.util.Map<String, java.util.Map<String, com.e2eq.framework.model.securityrules.RuleIndexSnapshot.Outcome>>> matrix,
                                                  java.util.List<Rule> rules) {
        // Group candidates by area/domain/action from header (wildcards preserved)
        java.util.Map<String, java.util.List<Rule>> bucket = new java.util.HashMap<>();
        for (Rule r : rules) {
            if (r == null || r.getSecurityURI() == null || r.getSecurityURI().getHeader() == null) continue;
            com.e2eq.framework.model.securityrules.SecurityURIHeader h = r.getSecurityURI().getHeader();
            String area = nz(h.getArea());
            String domain = nz(h.getFunctionalDomain());
            String action = nz(h.getAction());
            String key = area + "\u0001" + domain + "\u0001" + action;
            bucket.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(r);
        }
        for (java.util.Map.Entry<String, java.util.List<Rule>> e : bucket.entrySet()) {
            java.util.List<Rule> candidates = e.getValue();
            candidates.sort((a,b) -> {
                int s = Integer.compare(specificity(b), specificity(a));
                if (s != 0) return s;
                int p = Integer.compare(a.getPriority(), b.getPriority());
                if (p != 0) return p;
                String an = a.getName() == null ? "" : a.getName();
                String bn = b.getName() == null ? "" : b.getName();
                return an.compareToIgnoreCase(bn);
            });
            Rule winner = candidates.get(0);
            putOutcome(matrix, e.getKey(), winner);
        }
    }

    private static int specificity(Rule r) {
        com.e2eq.framework.model.securityrules.SecurityURIHeader h = r.getSecurityURI().getHeader();
        int s = 0;
        if (!"*".equals(nz(h.getArea()))) s++;
        if (!"*".equals(nz(h.getFunctionalDomain()))) s++;
        if (!"*".equals(nz(h.getAction()))) s++;
        return s;
    }

    private static String nz(String s) { return (s == null || s.trim().isEmpty()) ? "*" : s; }

    private static void putOutcome(java.util.Map<String, java.util.Map<String, java.util.Map<String, com.e2eq.framework.model.securityrules.RuleIndexSnapshot.Outcome>>> matrix,
                                   String compositeKey,
                                   Rule r) {
        String[] parts = compositeKey.split("\u0001");
        String area = parts[0], domain = parts[1], action = parts[2];
        com.e2eq.framework.model.securityrules.RuleIndexSnapshot.Outcome out = new com.e2eq.framework.model.securityrules.RuleIndexSnapshot.Outcome();
        out.setEffect(r.getEffect() != null ? r.getEffect().name() : null);
        out.setRule(r.getName());
        out.setPriority(r.getPriority());
        out.setFinalRule(r.isFinalRule());
        // best-effort source via header identity
        if (r.getSecurityURI() != null && r.getSecurityURI().getHeader() != null) {
            out.setSource(r.getSecurityURI().getHeader().getIdentity());
        }
        matrix.computeIfAbsent(area, a -> new java.util.HashMap<>())
                .computeIfAbsent(domain, d -> new java.util.HashMap<>())
                .put(action, out);
    }


    /**
     * this is called only by Quarkus upon startup if you create a rule context outside of injection
     * this will not be called
     */
    @PostConstruct
    public void ensureDefaultRules() {
        try {
            reloadFromRepo(defaultRealm);
        } catch (Exception e) {
            // Fallback to system rules only if repo hydration fails
            Log.warn("Policy hydration failed during startup; using system rules only", e);
            if (rules.isEmpty() || rulesForIdentity(securityUtils.getSystemSecurityHeader().getIdentity()).isEmpty()) {
                addSystemRules();
            }
        }
    }

    /**
     * Adds in the system rules regardless of any configuration that may be present and later added in
     */
    protected void addSystemRules() {
        // add default rules for the system
        // first explicitly add the "system"
        // to operate with in the security area
        SecurityURI suri = new SecurityURI(securityUtils.getSystemSecurityHeader(), securityUtils.getSystemSecurityBody());

        Rule systemRule = new Rule.Builder()
                .withName("SysAnyActionSecurity")
                .withDescription("System can take any action with in security")
                .withSecurityURI(suri)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(0)
                .withFinalRule(true).build();

        this.addRule(securityUtils.getSystemSecurityHeader(), systemRule);

        SecurityURIHeader header = securityUtils.getSystemSecurityHeader().clone();
        header.setIdentity("system");
        suri = new SecurityURI(header, securityUtils.getSystemSecurityBody());

        Rule systemRoleRule = new Rule.Builder()
                .withName("SysRoleAnyActionSecurity")
                .withDescription("system role can take any action with in security")
                .withSecurityURI(suri)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(1)
                .withFinalRule(true).build();
        this.addRule(header, systemRoleRule);

        // So this will match any user that has the role "user"
        // for "any area, any domain, and any action i.e. all areas, domains, and actions
        header = new SecurityURIHeader.Builder()
                .withIdentity("user")      // with the role "user"
                .withArea("*")             // any area
                .withFunctionalDomain("*") // any domain
                .withAction("*")           // any action
                .build();

        // This will match the resources
        // from "any" account, in the "b2bi" realm, any tenant, any owner, any datasegment
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*")       // any organization
                .withAccountNumber("*")    // any account
                .withRealm("*")            // within just the b2bi realm
                .withTenantId("*")         // any tenant
                .withOwnerId("*")          // any owner
                .withDataSegment("*")      // any datasegement
                .build();

        // Create the URI that represents this "rule" where by
        // for any one with the role "user", we want to consider this rule base for
        // all resources in the b2bi realm
        SecurityURI uri = new SecurityURI(header, body);

        // Create the first rule which will be a rule that
        // compares the userId of the principal, with the resource's ownerId
        // if they match then we allow the user to do what ever they are asking
        // we however can not allow them to delete themselves, so they can't
        // delete their credentials ( but can modify it ) and can't delete their
        // userProfile.

        // in this case
        // In the case we are reading we have a filter that constrains the result set
        // to where the ownerId is the same as the principalId
        Rule.Builder b = new Rule.Builder()
                .withName("view your own resources, limit to default dataSegment")
                .withSecurityURI(uri)
                .withAndFilterString("dataDomain.ownerId:${principalId}&&dataDomain.dataSegment:#0")
                .withEffect(RuleEffect.ALLOW)
                .withFinalRule(false);
        Rule r = b.build();

        this.addRule(header, r);

        header = new SecurityURIHeader.Builder()
                .withIdentity("user")         // with the role "admin"
                .withArea("Security")                 // any area
                .withFunctionalDomain("*") // any domain
                .withAction("DELETE")               // any action
                .build();

        uri = new SecurityURI(header, body);
        Rule.Builder userDenySecurityArea = new Rule.Builder()
                .withName("users can't delete anything in security area")
                .withSecurityURI(uri)
                .withAndFilterString("dataDomain.ownerId:${principalId}&&dataDomain.dataSegment:#0")
                .withEffect(RuleEffect.DENY)
                .withFinalRule(true);
        r = userDenySecurityArea.build();
        this.addRule(header, r);


        header = new SecurityURIHeader.Builder()
                .withIdentity("admin")         // with the role "admin"
                .withArea("*")                 // any area
                .withFunctionalDomain("*") // any domain
                .withAction("*")               // any action
                .build();

        // Now add one for a tenant level admin
        uri = new SecurityURI(header, body);
        Rule.Builder tenantAdminbuilder = new Rule.Builder()
                .withName("tenant admin can administer the tenant records")
                .withSecurityURI(uri)
                .withAndFilterString("dataDomain.tenantId:${pTenantId}")
                .withEffect(RuleEffect.ALLOW)
                .withFinalRule(true);
        r = tenantAdminbuilder.build();
        this.addRule(header, r);

        // set up anonymous actions
        header = new SecurityURIHeader.Builder()
                .withIdentity("ANONYMOUS")
                .withArea("onboarding")
                .withFunctionalDomain("registrationRequest")
                .withAction("create")
                .build();
        body = new SecurityURIBody.Builder()
                .withRealm(envConfigUtils.getSystemRealm())
                .withTenantId(envConfigUtils.getSystemTenantId())
                .withAccountNumber(envConfigUtils.getSystemAccountNumber())
                .withDataSegment("*")
                .withOwnerId("*")
                .withOrgRefName("*")
                .build();
        uri = new SecurityURI(header, body);

        Rule.Builder anonymousbuilder = new Rule.Builder()
                .withName("anonymous user can call register")
                .withSecurityURI(uri)
                .withAndFilterString("dataDomain.tenantId:${pTenantId}")
                .withEffect(RuleEffect.ALLOW)
                .withFinalRule(true);
        r = anonymousbuilder.build();
        this.addRule(header, r);

        header = new SecurityURIHeader.Builder()
                .withIdentity("ANONYMOUS")
                .withArea("website")
                .withFunctionalDomain("contactus")
                .withAction("create")
                .build();
        body = new SecurityURIBody.Builder()
                .withRealm(envConfigUtils.getSystemRealm())
                .withTenantId(envConfigUtils.getSystemTenantId())
                .withAccountNumber(envConfigUtils.getSystemAccountNumber())
                .withDataSegment("*")
                .withOwnerId("*")
                .withOrgRefName("*")
                .build();
        uri = new SecurityURI(header, body);

        anonymousbuilder = new Rule.Builder()
                .withName("anonymous user can call contactus")
                .withSecurityURI(uri)
                .withAndFilterString("dataDomain.tenantId:${pTenantId}")
                .withEffect(RuleEffect.ALLOW)
                .withFinalRule(true);
        r = anonymousbuilder.build();
        this.addRule(header, r);
    }

    /**
     * clears the rule base
     */
    public void clear() {
        rules.clear();
        compiledIndex = null;
    }

    private volatile long policyVersion = 0L;

    public long getPolicyVersion() { return policyVersion; }

    /**
     * Reloads the in-memory rule base from the persistent PolicyRepo for the given realm.
     * Keeps built-in system rules and then incorporates all rules from policies.
     */
    public synchronized void reloadFromRepo(@NotNull String realm) {
        // Reset in-memory rules and add system defaults
        clear();
        addSystemRules();

        try {
            // Establish a minimal SecurityContext to satisfy repo filters during hydration
            try {
                if (com.e2eq.framework.model.securityrules.SecurityContext.getPrincipalContext().isEmpty()) {
                    PrincipalContext pc = new PrincipalContext.Builder()
                            .withDefaultRealm(realm)
                            .withDataDomain(securityUtils.getSystemDataDomain())
                            .withUserId(envConfigUtils.getSystemUserId())
                            .withRoles(new String[]{"admin", "user"})
                            .build();
                    com.e2eq.framework.model.securityrules.SecurityContext.setPrincipalContext(pc);
                }
                if (com.e2eq.framework.model.securityrules.SecurityContext.getResourceContext().isEmpty()) {
                    com.e2eq.framework.model.securityrules.SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
                }
            } catch (Exception ignore) {
                // If context setup fails, repo calls may still work depending on configuration
            }

            // Fetch all policies in realm (bypass permission filters)
           if (policyRepo != null) {
              java.util.List<com.e2eq.framework.model.security.Policy> policies = policyRepo.getAllListIgnoreRules(realm);
              if (policies != null) {
                 for (com.e2eq.framework.model.security.Policy p : policies) {
                    if (p.getRules() == null) continue;
                    for (Rule r : p.getRules()) {
                       String identity = null;
                       if (r.getSecurityURI() != null && r.getSecurityURI().getHeader() != null) {
                          identity = r.getSecurityURI().getHeader().getIdentity();
                       }
                       if (identity == null || identity.isBlank()) {
                          identity = p.getPrincipalId();
                       }
                       if (identity == null || identity.isBlank()) {
                          Log.warnf("Rule:%s dod mpt jave an identity specified:", r.toString());
                          // skip malformed entries
                          continue;
                       }
                       // Build a header solely to key by identity; addRule uses identity for indexing
                       SecurityURIHeader header = new SecurityURIHeader.Builder()
                                                     .withIdentity(identity)
                                                     .withArea("*")
                                                     .withFunctionalDomain("*")
                                                     .withAction("*")
                                                     .build();
                       addRule(header, r);
                    }
                 }
              }
              // Sort each identity list by priority once for stable merge later
              for (Map.Entry<String, List<Rule>> e : rules.entrySet()) {
                 List<Rule> list = e.getValue();
                 if (list != null && list.size() > 1) {
                    list.sort((r1, r2) -> Integer.compare(r1.getPriority(), r2.getPriority()));
                 }
              }
              policyVersion = System.nanoTime();
              Log.infof("RuleContext: loaded %d rules from repo for realm %s", rules.size(), realm);
           }
        } catch (Exception ex) {
            Log.error("Failed to load policies into RuleContext; retaining system rules only", ex);
        }
        // (Re)build compiled index if enabled
        if (indexEnabled) {
            long start = System.nanoTime();
            List<Rule> all = new ArrayList<>();
            for (List<Rule> l : rules.values()) all.addAll(l);
            try {
                compiledIndex = RuleIndex.build(all);
                Log.infof("RuleContext: compiled index built in %d µs", (System.nanoTime() - start) / 1000);
            } catch (Throwable t) {
                Log.warn("RuleContext: failed to build compiled index; falling back to list scan", t);
                compiledIndex = null;
            }
        }
    }

    /**
     * Adds a rule to the rule base
     *
     * @param key  the header that will be used to match against and determine if the rule is applicable or not
     * @param rule the rule itself
     */
    public void addRule(@NotNull @Valid SecurityURIHeader key, @Valid @NotNull Rule rule) {
        // Store rules by identity
        List<Rule> list = rules.get(key.getIdentity());

        if (list == null) {
            list = new ArrayList<Rule>();
            rules.put(key.getIdentity(), list);
        }

        list.add(rule);

    }

    /**
     * Returns all the rules for a given identity
     *
     * @param identity the identity to get the rules for
     * @return an optional list of rules
     */
    public Optional<List<Rule>> rulesForIdentity(@NotNull String identity) {

        // return all the rules for this identity
        List<Rule> ruleList = rules.get(identity);

        if (ruleList == null) {
            return Optional.empty();
        }

        return Optional.of(ruleList);
    }

    /**
     * This will execute a script with a given principal and resource context, it will return true or false
     * based upon the evaluation of the script.  The script is assumed to return a boolean value
     *
     * @param pcontext the principal context
     * @param rcontext the resource context
     * @return true or false based upon the evaluation of the script
     */
    @Inject
    LabelService labelService;

    boolean runScript(PrincipalContext pcontext, ResourceContext rcontext, String script) {
        if (script == null || script.isBlank()) return false;

        // Resolve scripting config with fallback for non-CDI constructed instances
        boolean enabled = scriptingEnabled;
        boolean allowAll = scriptingAllowAllAccess;
        long timeoutMs = scriptingTimeoutMillis;
        try {
            org.eclipse.microprofile.config.Config cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
            if (cfg != null) {
                enabled = cfg.getOptionalValue("quantum.security.scripting.enabled", Boolean.class).orElse(Boolean.TRUE);
                allowAll = cfg.getOptionalValue("quantum.security.scripting.allowAllAccess", Boolean.class).orElse(Boolean.FALSE);
                timeoutMs = cfg.getOptionalValue("quantum.security.scripting.timeout.millis", Long.class).orElse(1500L);
            }
        } catch (Throwable ignored) {
            // fallback to injected fields which may be default-initialized when constructed manually
            if (timeoutMs <= 0) timeoutMs = 1500L;
        }
        // Ensure a reasonable minimum to account for engine warm-up
        if (timeoutMs < 500L) timeoutMs = 1500L;

        if (!enabled) {
            Log.warn("Security scripting is disabled via config; returning false");
            return false;
        }

        // Fallback legacy mode if explicitly allowed
        if (allowAll) {
            if (WARNED_PERMISSIVE.compareAndSet(false, true)) {
                Log.warn("quantum.security.scripting.allowAllAccess=true — running scripts with full host access (UNSAFE). This should only be used for compatibility.");
            }
            try (Context c = Context.newBuilder("js").allowAllAccess(true).build()) {
                var jsBindings = c.getBindings("js");
                jsBindings.putMember("pcontext", pcontext);
                jsBindings.putMember("rcontext", rcontext);
                installHelpersAndBindings(c, pcontext, rcontext);
                if (Log.isDebugEnabled()) Log.debugf("Executing script (permissive): %s", script);
                return c.eval("js", script).asBoolean();
            } catch (Throwable t) {
                Log.warn("Script execution failed in permissive mode; returning false", t);
                return false;
            }
        }

        // Hardened mode with timeout
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "rule-script-worker");
            th.setDaemon(true);
            return th;
        });
        try {
            java.util.concurrent.Future<Boolean> fut = executor.submit(() -> {
                Engine eng = Engine.newBuilder().build();
                try (Context c = Context.newBuilder("js")
                        .engine(eng)
                        .allowAllAccess(false)
                        .allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true).build())
                        .allowHostClassLookup(s -> false)
                        .allowIO(false)
                        .option("js.ecmascript-version", "2021")
                        .build()) {
                    var jsBindings = c.getBindings("js");
                    // Only expose simple data; avoid reflective Java method access
                    jsBindings.putMember("pcontext", pcontext);
                    jsBindings.putMember("rcontext", rcontext);
                    installHelpersAndBindings(c, pcontext, rcontext);
                    if (Log.isDebugEnabled()) Log.debugf("Executing script: %s", script);
                    Value v = c.eval("js", script);
                    return v.isBoolean() ? v.asBoolean() : false;
                }
            });
            return fut.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            Log.warnf("Script timed out after %d ms; returning false", (timeoutMs <= 0 ? 250L : timeoutMs));
            return false;
        } catch (Throwable t) {
            Log.warn("Script execution failed; returning false", t);
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Prepare helper bindings and safe maps for use inside scripts.
     */
    private void installHelpersAndBindings(Context c, PrincipalContext pcontext, ResourceContext rcontext) {
        var jsBindings = c.getBindings("js");

        // Prepare helper data structures
        Map<String,Object> sb = new HashMap<>();
        Map<String,Object> rctx = new HashMap<>();
        Map<String,Object> pctx = new HashMap<>();
        try {
            Set<String> labels = labelService != null ? labelService.labelsFor(rcontext) : Set.of();
            rctx.put("labels", new ArrayList<>(labels));
        } catch (Throwable ignored) {}
        try {
            Set<String> plabels = labelService != null ? labelService.labelsFor(pcontext) : Set.of();
            pctx.put("labels", new ArrayList<>(plabels));
        } catch (Throwable ignored) {}
        sb.put("rcontext", rctx);
        sb.put("pcontext", pctx);

        // Try to install helper functions (e.g., isA, hasLabel, ...)
        try {
            try {
                Class<?> cls = Class.forName("com.e2eq.ontology.policy.ScriptHelpers");
                java.lang.reflect.Method m = cls.getMethod("install", Map.class);
                m.invoke(null, sb);
            } catch (Throwable ignoredInner) {}
            Object isA = sb.get("isA"); if (isA != null) jsBindings.putMember("isA", isA);
            Object hasLabel = sb.get("hasLabel"); if (hasLabel != null) jsBindings.putMember("hasLabel", hasLabel);
            Object hasEdge = sb.get("hasEdge"); if (hasEdge != null) jsBindings.putMember("hasEdge", hasEdge);
            Object hasAnyEdge = sb.get("hasAnyEdge"); if (hasAnyEdge != null) jsBindings.putMember("hasAnyEdge", hasAnyEdge);
            Object hasAllEdges = sb.get("hasAllEdges"); if (hasAllEdges != null) jsBindings.putMember("hasAllEdges", hasAllEdges);
            Object relatedIds = sb.get("relatedIds"); if (relatedIds != null) jsBindings.putMember("relatedIds", relatedIds);
            Object noViolations = sb.get("noViolations"); if (noViolations != null) jsBindings.putMember("noViolations", noViolations);
        } catch (Throwable ignored) {}
    }

    /**
     * Create a new Header from the given identity and resource context
     *
     * @param identity the identity to use for the identity in the header
     * @param rcontext the values of area, functional domain, and action to be added to header
     * @return the newly created header
     */
    public SecurityURIHeader createHeaderFor(String identity, ResourceContext rcontext) {
        // Add principal rules
        return new SecurityURIHeader.Builder()
                .withIdentity(identity)
                .withArea(rcontext.getArea())
                .withFunctionalDomain(rcontext.getFunctionalDomain())
                .withAction(rcontext.getAction())
                .build();
    }




    List<Rule> getApplicableRulesForPrincipalAndAssociatedRoles(PrincipalContext pcontext, ResourceContext rcontext) {
        // If the compiled index is enabled and available, use it to quickly gather candidates
        if (indexEnabled && compiledIndex != null) {
           Log.warn("<< checking with index enabled >>");
            try {
                return compiledIndex.getApplicableRules(pcontext, rcontext);
            } catch (Throwable t) {
                Log.warn("RuleContext: compiled index lookup failed; falling back to list scan", t);
            }
        }

        // Legacy behavior: gather rules by identity and roles, then sort by priority
        List<Rule> applicableRules = new ArrayList<Rule>();
        // get the header for this pcontext and rcontext we are going to be comparing.
        SecurityURIHeader h = createHeaderFor(pcontext.getUserId(), rcontext);

        // find the rules that match this header
        Optional<List<Rule>> identityRules = rulesForIdentity(h.getIdentity());

        if (identityRules.isPresent()) {
            applicableRules.addAll(identityRules.get());
        }

        // Add role rules
        for (String role : pcontext.getRoles()) {
            identityRules = rulesForIdentity(role);
            if (identityRules.isPresent()) {
                applicableRules.addAll(identityRules.get());
            }
        }

        if (!applicableRules.isEmpty()) {
            // order the rules by priority if there are more than one.
            if (applicableRules.size() > 1) {
                Ordering<Rule> orderingByPriority = new Ordering<Rule>() {
                    @Override
                    public int compare(Rule r1, Rule r2) {
                        return r1.getPriority() - r2.getPriority();
                    }
                };
                applicableRules.sort(orderingByPriority);
            }
        }
        return applicableRules;
    }


    /**
     * This will check the rules for the given principal and resource context, it assumes a DENY final effect.
     *
     * @param pcontext the principal context
     * @param rcontext the resource context
     * @return
     */
    public SecurityCheckResponse checkRules( PrincipalContext pcontext,  ResourceContext rcontext) {
       Objects.requireNonNull(pcontext, "pcontext is required to be non null ");
       Objects.requireNonNull(rcontext, "rcontext is required to be non null ");
        return checkRules(pcontext, rcontext, null, null, RuleEffect.DENY);
    }

    /**
     * @param pcontext the context that represents the main user making the request
     * @param rcontext the resource that the user wants to take an action on
     * @param defaultFinalEffect the default effect that we start out with.  This typically can start out with DENY and then rules add
     *                           permissions, but sometimes we want to assume ALLOW and just remove permissions. This parameter determines
     *                           this default behavior.
     * @return
     */
    public SecurityCheckResponse checkRules(@Valid @NotNull PrincipalContext pcontext, @Valid @NotNull ResourceContext rcontext, @NotNull RuleEffect defaultFinalEffect) {
        return checkRules(pcontext, rcontext, null, null, defaultFinalEffect);
    }

    /**
     * New overload that allows in-memory filter applicability evaluation for a concrete resource instance.
     * Existing signatures delegate to this with nulls to preserve behavior when modelClass/resource are not supplied.
     */
    public SecurityCheckResponse checkRules(
            @Valid @NotNull PrincipalContext pcontext,
            @Valid @NotNull ResourceContext rcontext,
            Class<? extends UnversionedBaseModel> modelClass,
            Object resourceInstance,
            @NotNull RuleEffect defaultFinalEffect) {

        if (Log.isDebugEnabled()) {
            Log.debug("####  checking Permissions for pcontext:" + pcontext.toString() + " resource context:" + rcontext.toString());
        }

        // Create a response to show how we came to the conclusion
        SecurityCheckResponse response = new SecurityCheckResponse(pcontext, rcontext);


        // the final effect is defined by the defaultFinalEffect being passed in.
        // typically this will be DENY unless we have rules that allow it.
        // the rule base will have a default set in it.
        response.setFinalEffect(defaultFinalEffect);

        // Get the applicable rules for this pcontext and rcontext this will include
        // the roles associated with the pcontext
        List<Rule> applicableRules = getApplicableRulesForPrincipalAndAssociatedRoles(pcontext, rcontext);

        if (Log.isDebugEnabled()) {
            Log.debug("Applicable rules:" + applicableRules.size());
            if (applicableRules.size() > 0) {
               for (Rule r : applicableRules) {
                    Log.debug("  " + r.toString());
               }
            } else {
               Log.warnf("No rules found for pcontext:%s and rcontext:%s", pcontext.toString(), rcontext.toString());
            }
        }

        // expand the set of uri's for this pcontext and rcontext and save it for debug purposes into the response
        // not this is not used in the logic that follows and is just for debug
        // TODO refactor getApplicableRules to take in the expanded set of uri's that way its only calculated once
        List<SecurityURI> expandedUris = expandURIPrincipalIdentities(pcontext, rcontext);
        response.getApplicableSecurityURIs().addAll(expandedUris);

        if (Log.isDebugEnabled()) {
            Log.debug("");
            Log.debug("--- Extended Applicable rules:" + applicableRules.size());
        }

        // iterate over all the applicable rules
        // Track a SCOPED candidate when rules match but constraints (filters/scripts) are not evaluated
        RuleEffect scopedCandidateEffect = null;
        List<com.e2eq.framework.model.securityrules.SecurityCheckResponse.RuleFilterInfo> scopedFilterInfos = new ArrayList<>();
        String scopedScriptDetail = null;
        String scopedCandidateRuleName = null;
        Integer scopedCandidateRulePriority = null;
        Boolean scopedCandidateRuleFinal = null;
        int scopedCandidateScore = -1; // prefer filters(2) > script(1) > none(0)
        boolean complete = false;
        for (Rule r : applicableRules) {
            // record the rule we are evaluating for debug purposes
            response.getEvaluatedRules().add(r);

            if (Log.isDebugEnabled()) {
                Log.debug(" rule:" + r.getName() + "compared to uris:" + expandedUris.size());
            }

            // for each uri in the expanded set of uris which includes the principal userId and associated roles
            for (SecurityURI uri : expandedUris) {

                if (Log.isDebugEnabled()) {
                    Log.debug("Comparing:" + uri.uriString());
                    Log.debug("To ruleName:" + r.getName() + " URI:" + r.getSecurityURI().uriString());
                    Log.debug("");
                }

                // compare the uri to the rule uri to see if it matches ie. the rule is applicable
                if (WildCardMatcher.wildcardMatch(uri.uriString(), r.getSecurityURI().uriString(),
                        IOCase.INSENSITIVE)) {
                    // the rule is applicable.  Check the precondition and post conditions scripts
                    RuleResult result = new RuleResult(r);
                    MatchEvent matchEvent =
                            MatchEvent.builder()
                                    .principalUriString(uri.uriString())
                                    .ruleUriString(r.getSecurityURI().uriString())
                                    .ruleName(r.getName())
                                    .matched(true)
                                    .difference(StringUtils.difference(uri.uriString(), r.getSecurityURI().uriString()))
                                    .build();

                    // Capture eval mode (may be provided by evalMode overload via TL)
                    com.e2eq.framework.model.securityrules.EvalMode evalMode = getEvalModeForThread();

                    // 1) Evaluate precondition if present; a false result makes this rule NOT_APPLICABLE
                    if (r.getPreconditionScript() != null && !r.getPreconditionScript().isBlank()) {
                        boolean preOk = false;
                        try {
                            preOk = runScript(pcontext, rcontext, r.getPreconditionScript());
                        } catch (Throwable t) {
                            // Treat failures as false and continue; script engines can throw
                            Log.warnf(t, "Precondition script failed for rule '%s'", r.getName());
                            preOk = false;
                        }
                        matchEvent.setPreScript(r.getPreconditionScript());
                        matchEvent.setPreScriptResult(preOk);
                        if (!preOk) {
                            // Mark this rule as not applicable and move on to next rule (skip effect/postcondition)
                            result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                            // Record NA reason (precondition)
                            response.getNotApplicable().add(
                                    new com.e2eq.framework.model.securityrules.SecurityCheckResponse.NotApplicableInfo(
                                            r.getName(), "PRECONDITION", "Precondition evaluated to false or failed"));
                            response.getMatchedRuleResults().add(result);
                            response.getMatchEvents().add(matchEvent);
                            // Move to next rule entirely
                            // Break out of URI loop and signal outer loop to continue
                            complete = false; // no finality triggered
                            // break inner loop and proceed to next rule
                            break;
                        }
                    }

                    // 2) Policy-driven handling when no resource provided for non-LIST actions and rule has filters
                    boolean noResourceProvided = (resourceInstance == null);
                    boolean ruleHasFilters = StringUtils.isNotBlank(r.getAndFilterString()) || StringUtils.isNotBlank(r.getOrFilterString());
                    boolean ruleHasPostScript = (r.getPostconditionScript() != null && !r.getPostconditionScript().isBlank());
                    boolean isListAction = "list".equalsIgnoreCase(rcontext.getAction());
                    boolean strictSkipPost = false;
                    if (noResourceProvided && ruleHasFilters && !isListAction) {
                        // Determine policy from configuration; default is DEFER to preserve legacy behavior in tests
                        String policy = getNoResourceFilterPolicy();
                        boolean conservativeNA = "CONSERVATIVE_NA".equalsIgnoreCase(policy);

                        matchEvent.setFilterAndString(r.getAndFilterString());
                        matchEvent.setFilterOrString(r.getOrFilterString());
                        matchEvent.setFilterJoinOp(r.getJoinOp() != null ? r.getJoinOp().name() : "AND");
                        matchEvent.setFilterEvaluated(false);
                        matchEvent.setFilterResult(null);

                        // STRICT mode overrides conservative/DEFER behavior to surface SCOPED instead of NA
                        if (evalMode == com.e2eq.framework.model.securityrules.EvalMode.STRICT) {
                            matchEvent.setFilterReason("No resource provided; STRICT mode ⇒ SCOPED candidate");
                            response.setFilterConstraintsPresent(true);
                            response.getFilterConstraints().add(
                                    new SecurityCheckResponse.RuleFilterInfo(
                                            r.getName(),
                                            r.getAndFilterString(),
                                            r.getOrFilterString(),
                                            r.getJoinOp() != null ? r.getJoinOp().name() : "AND"
                                    )
                            );
                            // Record/upgrade SCOPED candidate (prefer richer constraints then priority)
                            {
                                int score = ruleHasFilters ? 2 : (ruleHasPostScript ? 1 : 0);
                                boolean replace = (scopedCandidateRuleName == null)
                                        || (score > scopedCandidateScore)
                                        || (score == scopedCandidateScore && r.getPriority() < (scopedCandidateRulePriority != null ? scopedCandidateRulePriority : Integer.MAX_VALUE));
                                if (replace) {
                                    scopedCandidateEffect = r.getEffect();
                                    scopedFilterInfos.clear();
                                    scopedFilterInfos.add(new SecurityCheckResponse.RuleFilterInfo(
                                            r.getName(), r.getAndFilterString(), r.getOrFilterString(),
                                            r.getJoinOp() != null ? r.getJoinOp().name() : "AND"));
                                    scopedScriptDetail = ruleHasPostScript ? r.getPostconditionScript() : null;
                                    scopedCandidateRuleName = r.getName();
                                    scopedCandidateRulePriority = r.getPriority();
                                    scopedCandidateRuleFinal = r.isFinalRule();
                                    scopedCandidateScore = score;
                                }
                            }
                            // In STRICT with no resource, do not evaluate postconditions for this rule
                            strictSkipPost = true;
                        } else if (conservativeNA) {
                            // Mark NOT_APPLICABLE to err on the side of safety when we cannot evaluate filters
                            matchEvent.setFilterReason("No resource provided; policy=CONSERVATIVE_NA");
                            result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                            // Record NA reason (policy / filter without resource)
                            response.getNotApplicable().add(
                                    new com.e2eq.framework.model.securityrules.SecurityCheckResponse.NotApplicableInfo(
                                            r.getName(), "FILTER", "No resource provided; conservative NA policy"));
                            response.getMatchedRuleResults().add(result);
                            response.getMatchEvents().add(matchEvent);
                            complete = false;
                            break;
                        } else {
                            // DEFER policy: do not suppress; surface constraints and proceed as legacy behavior
                            matchEvent.setFilterReason("No resource provided; policy=DEFER");
                            response.setFilterConstraintsPresent(true);
                            response.getFilterConstraints().add(
                                    new SecurityCheckResponse.RuleFilterInfo(
                                            r.getName(),
                                            r.getAndFilterString(),
                                            r.getOrFilterString(),
                                            r.getJoinOp() != null ? r.getJoinOp().name() : "AND"
                                    )
                            );
                            // Consider SCOPED candidate with selection heuristic
                            {
                                int score = ruleHasFilters ? 2 : (ruleHasPostScript ? 1 : 0);
                                boolean replace = (scopedCandidateRuleName == null)
                                        || (score > scopedCandidateScore)
                                        || (score == scopedCandidateScore && r.getPriority() < (scopedCandidateRulePriority != null ? scopedCandidateRulePriority : Integer.MAX_VALUE));
                                if (replace) {
                                    scopedCandidateEffect = r.getEffect();
                                    scopedFilterInfos.clear();
                                    scopedFilterInfos.add(new SecurityCheckResponse.RuleFilterInfo(
                                            r.getName(), r.getAndFilterString(), r.getOrFilterString(),
                                            r.getJoinOp() != null ? r.getJoinOp().name() : "AND"));
                                    scopedScriptDetail = ruleHasPostScript ? r.getPostconditionScript() : null;
                                    scopedCandidateRuleName = r.getName();
                                    scopedCandidateRulePriority = r.getPriority();
                                    scopedCandidateRuleFinal = r.isFinalRule();
                                    scopedCandidateScore = score;
                                }
                            }
                            // continue without breaking; postconditions/effects apply
                        }
                    }

                    // For LIST without a resource, do not suppress; signal constraints on the response
                    if (noResourceProvided && ruleHasFilters && isListAction) {
                        response.setFilterConstraintsPresent(true);
                        response.getFilterConstraints().add(
                                new SecurityCheckResponse.RuleFilterInfo(
                                        r.getName(),
                                        r.getAndFilterString(),
                                        r.getOrFilterString(),
                                        r.getJoinOp() != null ? r.getJoinOp().name() : "AND"
                                )
                        );
                        {
                            int score = ruleHasFilters ? 2 : (ruleHasPostScript ? 1 : 0);
                            boolean replace = (scopedCandidateRuleName == null)
                                    || (score > scopedCandidateScore)
                                    || (score == scopedCandidateScore && r.getPriority() < (scopedCandidateRulePriority != null ? scopedCandidateRulePriority : Integer.MAX_VALUE));
                            if (replace) {
                                scopedCandidateEffect = r.getEffect();
                                scopedFilterInfos.clear();
                                scopedFilterInfos.add(new SecurityCheckResponse.RuleFilterInfo(
                                        r.getName(), r.getAndFilterString(), r.getOrFilterString(),
                                        r.getJoinOp() != null ? r.getJoinOp().name() : "AND"));
                                scopedScriptDetail = ruleHasPostScript ? r.getPostconditionScript() : null;
                                scopedCandidateRuleName = r.getName();
                                scopedCandidateRulePriority = r.getPriority();
                                scopedCandidateRuleFinal = r.isFinalRule();
                                scopedCandidateScore = score;
                            }
                        }
                        // Continue as usual (postcondition/effect)
                    }

                    // 3) NEW: Filter applicability evaluation (optional, non-blocking)
                    // If modelClass/resourceInstance are provided, attempt in-memory evaluation of rule filter strings.
                    Optional<Boolean> filterOk = Optional.empty();
                    try {
                        // Only run evaluator when a concrete resource instance is provided.
                        if (resourceInstance != null) {
                            filterOk = evaluateFilterApplicability(pcontext, rcontext, r, modelClass, resourceInstance, matchEvent);
                            // STRICT mode: if evaluator could not run (empty) for a rule with filters, treat as SCOPED candidate
                            if (evalMode == com.e2eq.framework.model.securityrules.EvalMode.STRICT
                                    && ruleHasFilters && filterOk.isEmpty()) {
                                if (matchEvent != null && matchEvent.getFilterReason() == null) {
                                    matchEvent.setFilterReason("STRICT: evaluator unavailable ⇒ SCOPED candidate");
                                }
                                int score = ruleHasFilters ? 2 : (ruleHasPostScript ? 1 : 0);
                                boolean replace = (scopedCandidateRuleName == null)
                                        || (score > scopedCandidateScore)
                                        || (score == scopedCandidateScore && r.getPriority() < (scopedCandidateRulePriority != null ? scopedCandidateRulePriority : Integer.MAX_VALUE));
                                if (replace) {
                                    scopedCandidateEffect = r.getEffect();
                                    scopedFilterInfos.clear();
                                    scopedFilterInfos.add(new SecurityCheckResponse.RuleFilterInfo(
                                            r.getName(), r.getAndFilterString(), r.getOrFilterString(),
                                            r.getJoinOp() != null ? r.getJoinOp().name() : "AND"));
                                    scopedScriptDetail = ruleHasPostScript ? r.getPostconditionScript() : null;
                                    scopedCandidateRuleName = r.getName();
                                    scopedCandidateRulePriority = r.getPriority();
                                    scopedCandidateRuleFinal = r.isFinalRule();
                                    scopedCandidateScore = score;
                                }
                                // In STRICT mode treat this rule as inconclusive: skip postcondition
                                strictSkipPost = true;
                            }
                        } else {
                            // Evaluator skipped in no-resource context; preserve legacy behavior
                            if (matchEvent != null) {
                                matchEvent.setFilterEvaluated(false);
                                matchEvent.setFilterResult(null);
                                if (matchEvent.getFilterReason() == null) {
                                    matchEvent.setFilterReason("Evaluator skipped: no resource instance");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // Never block; treat as not evaluated
                        if (Log.isDebugEnabled()) {
                            Log.debugf(t, "Filter applicability evaluation failed for rule '%s'", r.getName());
                        }
                    }

                    if (filterOk.isPresent() && !filterOk.get()) {
                        // Mark NOT_APPLICABLE due to filter mismatch; proceed to next rule
                        result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                        response.getNotApplicable().add(
                                new com.e2eq.framework.model.securityrules.SecurityCheckResponse.NotApplicableInfo(
                                        r.getName(), "FILTER", "Filter evaluation returned false"));
                        response.getMatchedRuleResults().add(result);
                        response.getMatchEvents().add(matchEvent);
                        complete = false;
                        break;
                    }

                    // 4) Postcondition (as previously implemented, but guard against script errors)
                    if (strictSkipPost) {
                        // In STRICT + no-resource (or evaluator unavailable), treat as inconclusive: mark NA for rule result
                        result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                        response.getMatchedRuleResults().add(result);
                        response.getMatchEvents().add(matchEvent);
                        // Do not apply effect or finality; continue scanning for EXACT decisions
                        continue;
                    }
                    if (r.getPostconditionScript() != null && !r.getPostconditionScript().isBlank()) {
                        boolean scriptResult = false;
                        try {
                            scriptResult = runScript(pcontext, rcontext, r.getPostconditionScript());
                        } catch (Throwable t) {
                            // Treat failures as false and continue; likely due to missing resource-only data
                            if (Log.isDebugEnabled()) {
                                Log.debugf(t, "Postcondition script failed for rule '%s'", r.getName());
                            }
                            scriptResult = false;
                            // If we could not execute postcondition (e.g., missing resource), capture a SCOPED candidate
                            if (noResourceProvided) {
                                int score = ruleHasFilters ? 2 : (ruleHasPostScript ? 1 : 0);
                                boolean replace = (scopedCandidateRuleName == null)
                                        || (score > scopedCandidateScore)
                                        || (score == scopedCandidateScore && r.getPriority() < (scopedCandidateRulePriority != null ? scopedCandidateRulePriority : Integer.MAX_VALUE));
                                if (replace) {
                                    scopedCandidateEffect = r.getEffect();
                                    scopedScriptDetail = r.getPostconditionScript();
                                    scopedCandidateRuleName = r.getName();
                                    scopedCandidateRulePriority = r.getPriority();
                                    scopedCandidateRuleFinal = r.isFinalRule();
                                    scopedCandidateScore = score;
                                    // filters might be absent here; keep existing list (script-only)
                                }
                            }
                        }
                        matchEvent.setPostScript(r.getPostconditionScript());
                        matchEvent.setPostScriptResult(scriptResult);
                        if (scriptResult) {
                            result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                            response.setFinalEffect(r.getEffect());
                            // EXACT: record winning rule metadata
                            response.setWinningRuleName(r.getName());
                            response.setWinningRulePriority(r.getPriority());
                            response.setWinningRuleFinal(r.isFinalRule());
                        } else {
                            result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                            response.getNotApplicable().add(
                                    new com.e2eq.framework.model.securityrules.SecurityCheckResponse.NotApplicableInfo(
                                            r.getName(), "POSTCONDITION", "Postcondition evaluated to false or failed"));
                        }
                    } else {
                        result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                        response.setFinalEffect(r.getEffect());
                        // EXACT (no post script): record winning rule
                        response.setWinningRuleName(r.getName());
                        response.setWinningRulePriority(r.getPriority());
                        response.setWinningRuleFinal(r.isFinalRule());
                    }

                    response.getMatchedRuleResults().add(result);
                    response.getMatchEvents().add(matchEvent);

                    if (r.isFinalRule()) {
                        complete = true;
                        break;
                    }
                } else {
                    String difference = StringUtils.difference(uri.uriString(), r.getSecurityURI().uriString());
                    if (Log.isDebugEnabled()) {
                       Log.debug("Comparing:");
                       Log.debug(uri.uriString());
                       Log.debug(r.getSecurityURI().uriString());
                    }
                    response.getMatchEvents().add(
                            MatchEvent.builder()
                                    .principalUriString(uri.uriString())
                                    .ruleUriString(r.getSecurityURI().uriString())
                                    .ruleName(r.getName())
                                    .matched(false)
                                    .difference(difference)
                                    .build());
                    if (Log.isDebugEnabled()) {
                        Log.debug(" >>>  Difference:" + difference);
                    } else {
                       Log.debug(">>> MATCH");
                    }
                }

            }
            if (Log.isDebugEnabled()) {
                Log.debug("");
                Log.debug(" -- Matched Rules:");
                for (RuleResult result : response.getMatchedRuleResults()) {
                    Log.debug("  " + result.getRule().getName() + " " + result.getDeterminedEffect());
                }
            }
            if (complete) {
                break;
            }
        }

        // If no EXACT decision was produced but we recorded a SCOPED candidate, surface it
        try {
            boolean anyMatchedNonNA = false;
            if (response.getMatchedRuleResults() != null && !response.getMatchedRuleResults().isEmpty()) {
                for (RuleResult rr : response.getMatchedRuleResults()) {
                    if (rr.getDeterminedEffect() != RuleDeterminedEffect.NOT_APPLICABLE) {
                        anyMatchedNonNA = true;
                        break;
                    }
                }
            }
            if (!anyMatchedNonNA && scopedCandidateEffect != null) {
                // Determine current eval mode to maintain backward compatibility
                com.e2eq.framework.model.securityrules.EvalMode mode = getEvalModeForThread();
                // Always set a SCOPED decision view for clients
                response.setDecision(scopedCandidateEffect.name());
                response.setDecisionScope("SCOPED");
                response.setScopedConstraintsPresent(true);
                // Populate scoped constraints from captured filter infos
                if (scopedFilterInfos != null) {
                    for (com.e2eq.framework.model.securityrules.SecurityCheckResponse.RuleFilterInfo info : scopedFilterInfos) {
                        response.getScopedConstraints().add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                                "FILTER",
                                (info.getAndFilterString() != null && !info.getAndFilterString().isBlank()) ? info.getAndFilterString() : info.getOrFilterString(),
                                info.getJoinOp()
                        ));
                    }
                }
                // Add SCRIPT constraint if present
                if (scopedScriptDetail != null && !scopedScriptDetail.isBlank()) {
                    response.getScopedConstraints().add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                            "SCRIPT", scopedScriptDetail, null
                    ));
                }
                // Record winning rule metadata for SCOPED
                response.setWinningRuleName(scopedCandidateRuleName);
                response.setWinningRulePriority(scopedCandidateRulePriority);
                response.setWinningRuleFinal(scopedCandidateRuleFinal);
                // In LEGACY mode, do NOT change finalEffect (preserve historical behavior expected by tests)
                // In AUTO/STRICT modes, promote SCOPED effect to finalEffect so callers relying only on finalEffect see it.
                if (mode != com.e2eq.framework.model.securityrules.EvalMode.LEGACY) {
                    response.setFinalEffect(scopedCandidateEffect);
                }
            }
        } catch (Throwable ignored) { }

        // Decorate decision fields for callers that use legacy overloads
        try {
            if (response.getDecision() == null && response.getFinalEffect() != null) {
                response.setDecision(response.getFinalEffect().name());
            }
            if (response.getEvalModeUsed() == null) {
                response.setEvalModeUsed(com.e2eq.framework.model.securityrules.EvalMode.LEGACY.name());
            }
            // Determine a simple scope: if any matched rule applied (not NA), mark EXACT; otherwise DEFAULT
            boolean anyMatchedNonNA = false;
            if (response.getMatchedRuleResults() != null && !response.getMatchedRuleResults().isEmpty()) {
                for (RuleResult rr : response.getMatchedRuleResults()) {
                    if (rr.getDeterminedEffect() != RuleDeterminedEffect.NOT_APPLICABLE) {
                        anyMatchedNonNA = true;
                        break;
                    }
                }
            }
            if (anyMatchedNonNA) {
                // If constraints were surfaced (e.g., LIST without resource), treat as SCOPED; else EXACT
                if (response.isFilterConstraintsPresent()) {
                    response.setDecisionScope("SCOPED");
                    if (response.getFilterConstraints() != null && !response.getFilterConstraints().isEmpty()) {
                        response.setScopedConstraintsPresent(true);
                        List<com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint> sc = new ArrayList<>();
                        for (com.e2eq.framework.model.securityrules.SecurityCheckResponse.RuleFilterInfo info : response.getFilterConstraints()) {
                            String joined = info.getJoinOp();
                            if (info.getAndFilterString() != null && !info.getAndFilterString().isBlank()) {
                                sc.add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                                        "FILTER", info.getAndFilterString(), joined));
                            }
                            if (info.getOrFilterString() != null && !info.getOrFilterString().isBlank()) {
                                sc.add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                                        "FILTER", info.getOrFilterString(), joined));
                            }
                        }
                        response.getScopedConstraints().addAll(sc);
                    }
                } else {
                    // Respect any pre-set SCOPED scope from candidate promotion; else mark EXACT
                    if (response.getDecisionScope() == null || response.getDecisionScope().isBlank()) {
                        response.setDecisionScope("EXACT");
                    }
                }
            } else {
                // If a SCOPED candidate was promoted, keep SCOPED; else DEFAULT
                if (response.getDecisionScope() == null || response.getDecisionScope().isBlank()) {
                    response.setDecisionScope("DEFAULT");
                }
                if (response.getFinalEffect() != null) {
                    String label = (response.getFinalEffect() == RuleEffect.ALLOW) ? "NA-ALLOW" : "NA-DENY";
                    response.setNaLabel(label);
                }
            }
        } catch (Throwable ignored) { }

        return response;
    }

    /**
     * New overload that accepts an EvalMode to control evaluator usage and response scoping semantics.
     * Legacy overloads default to LEGACY.
     */
    public SecurityCheckResponse checkRules(
            @Valid @NotNull PrincipalContext pcontext,
            @Valid @NotNull ResourceContext rcontext,
            Class<? extends UnversionedBaseModel> modelClass,
            Object resourceInstance,
            @NotNull RuleEffect defaultFinalEffect,
            com.e2eq.framework.model.securityrules.EvalMode evalMode) {
        // Set eval mode for this thread so core evaluation can react to it
        setEvalModeForThread(evalMode != null ? evalMode : com.e2eq.framework.model.securityrules.EvalMode.LEGACY);
        try {
            SecurityCheckResponse resp = checkRules(pcontext, rcontext, modelClass, resourceInstance, defaultFinalEffect);

            // Stamp eval mode used
            resp.setEvalModeUsed(getEvalModeForThread().name());

            // Ensure decision string present
            if (resp.getFinalEffect() != null && (resp.getDecision() == null || resp.getDecision().isBlank())) {
                resp.setDecision(resp.getFinalEffect().name());
            }

            // Ensure NA label for DEFAULT
            if ("DEFAULT".equals(resp.getDecisionScope()) && resp.getNaLabel() == null && resp.getFinalEffect() != null) {
                String label = (resp.getFinalEffect() == RuleEffect.ALLOW) ? "NA-ALLOW" : "NA-DENY";
                resp.setNaLabel(label);
            }

            // Backfill scopedConstraints from filterConstraints when SCOPED and empty (compat path)
            if ("SCOPED".equals(resp.getDecisionScope()) && (resp.getScopedConstraints() == null || resp.getScopedConstraints().isEmpty())) {
                if (resp.getFilterConstraints() != null && !resp.getFilterConstraints().isEmpty()) {
                    resp.setScopedConstraintsPresent(true);
                    List<com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint> sc = new ArrayList<>();
                    for (com.e2eq.framework.model.securityrules.SecurityCheckResponse.RuleFilterInfo info : resp.getFilterConstraints()) {
                        String joined = info.getJoinOp();
                        if (info.getAndFilterString() != null && !info.getAndFilterString().isBlank()) {
                            sc.add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                                    "FILTER", info.getAndFilterString(), joined));
                        }
                        if (info.getOrFilterString() != null && !info.getOrFilterString().isBlank()) {
                            sc.add(new com.e2eq.framework.model.securityrules.SecurityCheckResponse.ScopedConstraint(
                                    "FILTER", info.getOrFilterString(), joined));
                        }
                    }
                    resp.getScopedConstraints().addAll(sc);
                }
            }

            return resp;
        } finally {
            clearEvalModeForThread();
        }
    }


    /**
     * Attempt to evaluate rule filter strings as applicability constraints against a concrete resource.
     * This initial implementation is a safe stub that records trace fields and returns Optional.empty()
     * to preserve current behavior. A future iteration will compile predicates using QueryPredicates.
     *
     * Note: We intentionally keep this non-throwing. Any failure or missing context results in Optional.empty().
     */
    public Optional<Boolean> evaluateFilterApplicability(
            PrincipalContext pcontext,
            ResourceContext rcontext,
            Rule rule,
            Class<? extends UnversionedBaseModel> modelClass,
            Object resourceInstance,
            com.e2eq.framework.model.securityrules.MatchEvent matchEvent) {

        // Populate basic trace fields (additive)
        if (matchEvent != null && rule != null) {
            matchEvent.setFilterAndString(rule.getAndFilterString());
            matchEvent.setFilterOrString(rule.getOrFilterString());
            matchEvent.setFilterJoinOp(rule.getJoinOp() != null ? rule.getJoinOp().name() : "AND");
            matchEvent.setFilterEvaluated(false);
            matchEvent.setFilterResult(null);
            matchEvent.setFilterReason(null);
        }

        // Evaluator only runs when a concrete resource instance is provided. In no-resource contexts,
        // we cannot safely evaluate resource-dependent filters; defer to DB-side filtering.
        if (resourceInstance == null) {
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("No resource provided; evaluator disabled");
            }
            return Optional.empty();
        }

        // If the rule has no filters, we treat it as not needing evaluation
        boolean hasAnd = rule != null && org.apache.commons.lang3.StringUtils.isNotBlank(rule.getAndFilterString());
        boolean hasOr = rule != null && org.apache.commons.lang3.StringUtils.isNotBlank(rule.getOrFilterString());
        if (!hasAnd && !hasOr) {
            return Optional.of(true);
        }

        // resourceInstance is non-null past this point

        // Build facts JSON. We require at least ResourceContext and PrincipalContext; resourceInstance is optional.
        Map<String, Object> facts = new HashMap<>();
        try {
            // rcontext section (shallow)
            Map<String, Object> rcMap = new HashMap<>();
            rcMap.put("area", rcontext != null ? rcontext.getArea() : null);
            rcMap.put("functionalDomain", rcontext != null ? rcontext.getFunctionalDomain() : null);
            rcMap.put("action", rcontext != null ? rcontext.getAction() : null);
            rcMap.put("resourceId", rcontext != null ? rcontext.getResourceId() : null);
            facts.put("rcontext", rcMap);

            // dataDomain section (from principal)
            Map<String, Object> ddMap = new HashMap<>();
            if (pcontext != null && pcontext.getDataDomain() != null) {
                ddMap.put("orgRefName", pcontext.getDataDomain().getOrgRefName());
                ddMap.put("accountNum", pcontext.getDataDomain().getAccountNum());
                ddMap.put("tenantId", pcontext.getDataDomain().getTenantId());
                ddMap.put("dataSegment", pcontext.getDataDomain().getDataSegment());
                ddMap.put("ownerId", pcontext.getDataDomain().getOwnerId());
            }
            facts.put("dataDomain", ddMap);

            // resource section (optional, shallow)
            if (resourceInstance != null) {
                if (resourceInstance instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> rm = (Map<String, Object>) resourceInstance;
                    facts.put("resource", rm);
                } else {
                    // Convert POJO to a map-like JSON structure using Jackson
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode node = mapper.valueToTree(resourceInstance);
                    facts.put("resource", node);
                }
            }
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Failed to build facts for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Facts building failed");
            }
            return Optional.empty();
        }

        // Resolve variables used by filters (strings and objects)
        MorphiaUtils.VariableBundle vars;
        try {
            vars = resolveVariableBundle(pcontext, rcontext, modelClass);
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Failed to resolve variable bundle for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Variable resolution failed");
            }
            return Optional.empty();
        }

        // Compile predicates using the shared BIAPI compiler via reflection to avoid hard module dependency
        java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> andPred = null;
        java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> orPred = null;
        try {
            if (hasAnd) {
                andPred = tryCompilePredicate(rule.getAndFilterString(), vars.strings, vars.objects)
                        .orElse(null);
            }
            if (hasOr) {
                orPred = tryCompilePredicate(rule.getOrFilterString(), vars.strings, vars.objects)
                        .orElse(null);
            }
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Predicate compilation failed for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Predicate compilation failed");
            }
            return Optional.empty();
        }

        // Evaluate predicates against facts
        boolean andOk = true; // identity for AND when missing
        boolean orOk = true;  // identity when only one present and join=AND; will be overridden when used
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode factsNode = mapper.valueToTree(facts);
        try {
            if (andPred != null) {
                andOk = andPred.test(factsNode);
            }
            if (orPred != null) {
                orOk = orPred.test(factsNode);
            }
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Predicate evaluation failed for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Predicate evaluation failed");
            }
            return Optional.empty();
        }

        // Combine results according to joinOp when both present
        boolean result;
        if (hasAnd && hasOr) {
            FilterJoinOp op = rule.getJoinOp() != null ? rule.getJoinOp() : FilterJoinOp.AND;
            if (op == FilterJoinOp.OR) {
                result = andOk || orOk;
            } else {
                result = andOk && orOk;
            }
        } else if (hasAnd) {
            result = andOk;
        } else { // hasOr only
            result = orOk;
        }

        if (matchEvent != null) {
            matchEvent.setFilterEvaluated(true);
            matchEvent.setFilterResult(result);
            matchEvent.setFilterReason(null);
        }

        return Optional.of(result);
    }

    /**
     * Attempts to call QueryPredicates.compilePredicate(query, vars, objectVars) via reflection to avoid
     * creating a direct compile-time dependency from the morphia-repos module to quantum-framework.
     * If the class or method is unavailable, returns Optional.empty().
     */
    @SuppressWarnings("unchecked")
    private Optional<java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode>> tryCompilePredicate(
            String query,
            Map<String, String> vars,
            Map<String, Object> objectVars) {
        try {
            Class<?> qp = Class.forName("com.e2eq.framework.query.QueryPredicates");
            java.lang.reflect.Method m = qp.getMethod("compilePredicate", String.class, Map.class, Map.class);
            Object pred = m.invoke(null, query, vars, objectVars);
            return Optional.of((java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode>) pred);
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] QueryPredicates not available or invocation failed; falling back");
            }
            return Optional.empty();
        }
    }


    /**
     * Builds a list of SecurityURI for the principal and resource context.  This will expand upon the principle context
     * and get all the roles associated with the principal and add it to the list.
     *
     * @param pcontext
     * @param rcontext
     * @return
     */
    List<SecurityURI> expandURIPrincipalIdentities(@NotNull @Valid PrincipalContext pcontext, @NotNull @Valid ResourceContext rcontext) {
        List<SecurityURI> uris = new ArrayList<SecurityURI>();

        // Add the roles first because they probably will resolve first
        for (String role : pcontext.getRoles()) {
            uris.add(createURLForIdentity(role, pcontext, rcontext));
        }

        // Add the principal if role based rules don't work see if its explicitly defined
        uris.add(createURLForIdentity(pcontext.getUserId(), pcontext, rcontext));

        return uris;
    }

    /**
     * used by the expandURIPrincipalIdentities to create a SecurityURI for a given identity that mirors the
     * PrincipalContext and ResourceContext given.  Used when there are roles associated with the identity.
     *
     * @param identity the role or userId
     * @param pcontext the principal context to use to pull the realm, org, account, tenantid, and ownerId from
     * @param rcontext the resource context
     * @return
     */
    SecurityURI createURLForIdentity(@NotNull String identity, @NotNull @Valid PrincipalContext pcontext, @NotNull @Valid ResourceContext rcontext) {

        SecurityURIHeader.Builder huri = new SecurityURIHeader.Builder()
                .withIdentity(identity)
                .withArea(rcontext.getArea())
                .withFunctionalDomain(rcontext.getFunctionalDomain())
                .withAction(rcontext.getAction());


        SecurityURIBody.Builder buri = new SecurityURIBody.Builder()
                .withRealm(pcontext.getDefaultRealm())
                .withOrgRefName(pcontext.getDataDomain().getOrgRefName())
                .withAccountNumber(pcontext.getDataDomain().getAccountNum())
                .withTenantId(pcontext.getDataDomain().getTenantId())
                .withOwnerId(identity) // TODO not sure of this but here a given role would be the owner
                .withDataSegment(Integer.toString(pcontext.getDataDomain().getDataSegment()));

        buri.withResourceId(rcontext.getResourceId()); // can't be optional because its used in scripts
        SecurityURIHeader header = huri.build();
        SecurityURIBody body = buri.build();

        return new SecurityURI(header, body);
    }

    /**
     * Resolves the variable bundle (string and object variables) for the given request context by invoking
     * all AccessListResolver beans that apply. The resulting maps can be used for Morphia filters and in-memory
     * predicate compilation.
     *
     * @param pcontext the principal context containing user identity and permissions
     * @param rcontext the resource context describing the target resource
     * @param modelClass the model class being queried
     * @return a VariableBundle containing resolved string and object variables from all applicable resolvers
     */
    public MorphiaUtils.VariableBundle resolveVariableBundle(
            @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext,
            @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext,
            Class<? extends UnversionedBaseModel> modelClass
    ) {
        Map<String, Object> extraObjects = new HashMap<>();
        if (resolvers != null) {
            for (AccessListResolver r : resolvers) {
                try {
                    if (r.supports(pcontext, rcontext, modelClass)) {
                        extraObjects.put(r.key(), r.resolve(pcontext, rcontext, modelClass));
                    }
                } catch (Exception e) {
                    Log.warnf(e, "AccessListResolver '%s' failed; continuing without it", r.getClass().getName());
                }
            }
        }
        return MorphiaUtils.buildVariableBundle(pcontext, rcontext, extraObjects);
    }

    /**
     * Get the list of filters that are applicable for the given principal and resource context.
     * @param ifilters
     * @param pcontext
     * @param rcontext
     * @return
     */
    public List<Filter> getFilters(List<Filter> ifilters, @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext, @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext, Class<? extends UnversionedBaseModel> modelClass) {
        List<Filter> filters = new ArrayList<>();
        filters.addAll(ifilters);

        SecurityCheckResponse response = this.checkRules(pcontext, rcontext);

        List<Filter> andFilters = new ArrayList<>();
        List<Filter> orFilters = new ArrayList<>();

        MorphiaUtils.VariableBundle vars = resolveVariableBundle(pcontext, rcontext, modelClass);

        for (RuleResult result : response.getMatchedRuleResults()) {
            // ignore Not Applicable rules
            if (result.getDeterminedEffect() == RuleDeterminedEffect.NOT_APPLICABLE) {
                continue;
            }

            Rule rule = result.getRule();
            if (rule.getAndFilterString() != null && !rule.getAndFilterString().isEmpty()) {
                andFilters.add(MorphiaUtils.convertToFilter(rule.getAndFilterString(), vars, modelClass));
            }

            if (rule.getOrFilterString() != null && !rule.getOrFilterString().isEmpty()) {
                orFilters.add(MorphiaUtils.convertToFilter(rule.getOrFilterString(), vars, modelClass));
            }

            if (!andFilters.isEmpty() && !orFilters.isEmpty()) {
                FilterJoinOp joinOp;
                if (rule.getJoinOp() != null) {
                    joinOp = rule.getJoinOp();
                } else {
                    joinOp = FilterJoinOp.AND;
                }
                if (joinOp == FilterJoinOp.AND) {
                    andFilters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
                    filters.add(Filters.and(andFilters.toArray(new Filter[andFilters.size()])));
                } else {
                    orFilters.add(Filters.and(andFilters.toArray(new Filter[andFilters.size()])));
                    // Correct OR combination should use Filters.or
                    filters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
                }
            } else {
                if (!andFilters.isEmpty()) {
                    filters.addAll(andFilters);
                    andFilters.clear();
                } else {
                    if (!orFilters.isEmpty()) {
                        filters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
                        orFilters.clear();
                    }
                }
            }
            if (rule.isFinalRule()) {
                break;
            }
        }


        // Sucks that we have to do this but Filter does not implement equals there for
        // gets hosed if your using a set.
        List<Filter> rc = new ArrayList<>();
        HashMap<String, Filter> filterMap = new HashMap<>();
        filters.forEach(filter -> {
            filterMap.put(filter.toString(), filter);
        });
        rc.addAll(filterMap.values());

        return rc;
    }


    public String getRealmId(PrincipalContext principalContext, ResourceContext resourceContext) {
        if (principalContext != null)
            return principalContext.getDefaultRealm();
        else
            return defaultRealm;
    }


    /** TODO Given the rule context is application scoped, perhaps it needs to be request scoped because the rules maay change
     *   if its application scoped then this initRules shoulds not be public
     * @param area
     * @param functionalDomain
     * @param userId
     */
    public void initDefaultRules( String area, String functionalDomain, String userId) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                                      .withAction("*")
                                      .withIdentity(userId)
                                      .withArea(area)
                                      .withFunctionalDomain(functionalDomain).build();

        SecurityURIBody body = new SecurityURIBody.Builder()
                                  .withOrgRefName(envConfigUtils.getTestOrgRefName())
                                  .withAccountNumber(envConfigUtils.getTestAccountNumber())
                                  .withRealm(envConfigUtils.getTestRealm())
                                  .withOwnerId(userId)
                                  .withTenantId(envConfigUtils.getTestTenantId()).build();


        addRule(header,
           new Rule.Builder()
              .withName("allow any")
              .withSecurityURI(
                 new SecurityURI(header, body)
              )
              .withEffect(RuleEffect.ALLOW).build());

        header = header.clone();
        header.setAction("view");
        addRule(header,
           new Rule.Builder()
              .withName("allow view")
              .withSecurityURI(
                 new SecurityURI(header, body.clone())
              )
              .withEffect(RuleEffect.ALLOW).build());
    }
}
