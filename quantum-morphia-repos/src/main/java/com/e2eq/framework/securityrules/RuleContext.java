package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
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
import org.apache.commons.text.StringSubstitutor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.graalvm.polyglot.Context;


import java.util.*;


@ApplicationScoped
public class RuleContext {

    @Inject
    Instance<AccessListResolver> resolvers;
     @Inject
     SecurityUtils securityUtils;

     @Inject
     EnvConfigUtils envConfigUtils;

     @Inject
     com.e2eq.framework.model.persistent.morphia.PolicyRepo policyRepo;

    /**
     * This holds a map of rules, indexed by an "identity" where an identity may be either
     * a specific userId, or a role name.
     */
    Map<String, List<Rule>> rules = new HashMap<>();

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "system-com")
    protected String defaultRealm;

    public RuleContext(SecurityUtils securityUtils, EnvConfigUtils envConfigUtils) {
        this.securityUtils = securityUtils;
        this.envConfigUtils = envConfigUtils;
    }

    public RuleContext() {
        // load rules
        //TODO: Need to understand how to determine tenant, account etc on initialization
        Log.debug("Creating ruleContext");
    }


    public String getDefaultRealm() {
        return defaultRealm;
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
        } catch (Exception ex) {
            Log.error("Failed to load policies into RuleContext; retaining system rules only", ex);
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
    boolean runScript(PrincipalContext pcontext, ResourceContext rcontext, String script) {
        // Context c = Context.newBuilder().allowAllAccess(true).build();
        Context c = Context.newBuilder().allowAllAccess(true).build();
        c.getBindings("js").putMember("pcontext", pcontext);
        c.getBindings("js").putMember("rcontext", rcontext);

        boolean allow = c.eval("js", script).asBoolean();
        return allow;

    }

    /**
     * Create a new Header from the given identity and resource context
     *
     * @param identity the identity to use for the identity in the header
     * @param rcontext the values of area, functional domain, and action to be added to header
     * @return the newly created header
     */
    SecurityURIHeader createHeaderFor(String identity, ResourceContext rcontext) {
        // Add principal rules
        return new SecurityURIHeader.Builder()
                .withIdentity(identity)
                .withArea(rcontext.getArea())
                .withFunctionalDomain(rcontext.getFunctionalDomain())
                .withAction(rcontext.getAction())
                .build();
    }




    List<Rule> getApplicableRulesForPrincipalAndAssociatedRoles(PrincipalContext pcontext, ResourceContext rcontext) {
        // holder for the applicable rules
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
    public SecurityCheckResponse checkRules(@Valid @NotNull PrincipalContext pcontext, @Valid @NotNull ResourceContext rcontext) {
        return checkRules(pcontext, rcontext, RuleEffect.DENY);
    }

    /**
     * @param pcontext the context that represents the main user making the request
     * @param rcontext the resource that the user wants to take an action on
     * @param defaultFinalEffect the default effect that we start out with.  This typically can start out with DENY and then rules add
     *                           permissions, but sometimes we want to assume ALLOW and just remove permissions. This parameeter determins
     *                           this default behavior.
     * @return
     */
    public SecurityCheckResponse checkRules(@Valid @NotNull PrincipalContext pcontext, @Valid @NotNull ResourceContext rcontext, @NotNull RuleEffect defaultFinalEffect) {

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

        // expand the set of uri's for this pcontext and rcontext and save it for debug purposes into the response
        // not this is not used in the logic that follows and is just for debug
        // TODO refactor getApplicableRules to take in the expanded set of uri's that way its only calculated once
        List<SecurityURI> expandedUris = expandURIPrincipalIdentities(pcontext, rcontext);
        response.getApplicableSecurityURIs().addAll(expandedUris);

        if (Log.isDebugEnabled()) {
            Log.debug("");
            Log.debug("--- Applicable rules:" + applicableRules.size());
        }

        // iterate over all the applicable rules
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
                    Log.debug("Comparing:" + uri.getURIString());
                    Log.debug("To ruleName:" + r.getName() + " URI:" + r.getSecurityURI().getURIString());
                    Log.debug("");
                }

                // compare the uri to the rule uri to see if it matches ie. the rule is applicable
                if (WildCardMatcher.wildcardMatch(uri.getURIString(), r.getSecurityURI().getURIString(),
                        IOCase.INSENSITIVE)) {
                    // the rule is applicable.  Check the precondition and post conditions scripts
                    RuleResult result = new RuleResult(r);
                    MatchEvent matchEvent =
                            MatchEvent.builder()
                                    .principalUriString(uri.getURIString())
                                    .ruleUriString(r.getSecurityURI().getURIString())
                                    .ruleName(r.getName())
                                    .matched(true)
                                    .difference(StringUtils.difference(uri.getURIString(), r.getSecurityURI().getURIString()))
                                    .build();


                    if (r.getPostconditionScript() != null) {
                        boolean scriptResult = runScript(pcontext, rcontext, r.getPostconditionScript());
                        matchEvent.setPostScript(r.getPostconditionScript());
                        matchEvent.setPostScriptResult(scriptResult);
                        if (scriptResult) {
                            result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                            response.setFinalEffect(r.getEffect());
                        } else {
                            result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                        }
                    } else {
                        result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                        response.setFinalEffect(r.getEffect());
                    }

                    response.getMatchedRuleResults().add(result);
                    response.getMatchEvents().add(matchEvent);

                    if (r.isFinalRule()) {
                        complete = true;
                        break;
                    }
                } else {
                    String difference = StringUtils.difference(uri.getURIString(), r.getSecurityURI().getURIString());
                    response.getMatchEvents().add(
                            MatchEvent.builder()
                                    .principalUriString(uri.getURIString())
                                    .ruleUriString(r.getSecurityURI().getURIString())
                                    .ruleName(r.getName())
                                    .matched(false)
                                    .difference(difference)
                                    .build());
                    if (Log.isDebugEnabled()) {
                        Log.debug(" >>>  Difference:" + difference);
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
        return response;
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
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(pcontext, rcontext, extraObjects);

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
                    filters.add(Filters.and(orFilters.toArray(new Filter[orFilters.size()])));
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
