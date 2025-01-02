package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.util.IOCase;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.WildCardMatcher;
import com.google.common.collect.Ordering;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import io.quarkus.runtime.BuilderConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.graalvm.polyglot.Context;

import java.util.*;


@ApplicationScoped
public class RuleContext {
    /**
     * This holds a map of rules, indexed by an "identity" where an identity may be either
     * a specific userId, or a role name.
     */
    Map<String, List<Rule>> rules = new HashMap<>();

    public static String DefaultRealm = "system-com";

    public RuleContext() {
        // load rules
        //TODO: Need to understand how to determine tenant, account etc on initialization
        Log.debug("Creating ruleContext");
    }

    @jakarta.inject.Inject
    public RuleContext(BuilderConfig builderConfig) {
    }

    /**
     * this is called only by Quarkus upon startup if you create a rule context outside of injection
     * this will not be called
     */
    @PostConstruct
    public void ensureDefaultRules() {
        if (rules.isEmpty()) {
            addSystemRules();
        } else {
            // Look for system Rules
            if (rulesFor(SecurityUtils.systemSecurityHeader).isEmpty()) {
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
        SecurityURI suri = new SecurityURI(SecurityUtils.systemSecurityHeader, SecurityUtils.systemSecurityBody);

        Rule systemRule = new Rule.Builder()
                .withName("SysAnyActionSecurity")
                .withDescription("System can take any action with in security")
                .withSecurityURI(suri)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(0)
                .withFinalRule(true).build();

        this.addRule(SecurityUtils.systemSecurityHeader, systemRule);

        SecurityURIHeader header = SecurityUtils.systemSecurityHeader.clone();
        header.setIdentity("system");
        suri = new SecurityURI(header, SecurityUtils.systemSecurityBody);

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
                .withRealm(SecurityUtils.systemRealm)
                .withTenantId(SecurityUtils.systemTenantId)
                .withAccountNumber(SecurityUtils.systemAccountNumber)
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
                .withRealm(SecurityUtils.systemRealm)
                .withTenantId(SecurityUtils.systemTenantId)
                .withAccountNumber(SecurityUtils.systemAccountNumber)
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


        // Now we are creating another URI, however this one is more specific than the last one
        // in this case we are creating something that is again for the role "user",
        // however this time it will only match any area, and FD, but only the "view" action
        //
        // The body says only for resources from the b2bi realm that are owned by "system@b2bintegrator.com"
        // ie. are system objects
     /* header = new SecurityURIHeader.Builder()
         .withIdentity("user")
         .withArea("*")                      // any area
         .withFunctionalDomain("*")          // any domain
         .withAction("view")                 // view action
         .build();
      body = new SecurityURIBody.Builder()
         .withOrgRefName(SecurityUtils.systemOrgRefName)
         .withAccountNumber("*")             // any account
         .withRealm(SecurityUtils.systemRealm)     // within just the b2bi realm
         .withTenantId("*")                  // any tenant
         .withOwnerId(SecurityUtils.systemUserId)  // system owner
         .withDataSegment("*")               // any data segment
         .build();

      uri = new SecurityURI(header, body);

      // Now we are adding a rule that says that we will allow with this
      // matching criteria, however the filter string here is for "ownerId:system@b2bintegrator.com"
      // its or'ed in which means that if this were to be added we would or this filter compared to
      // others.
      b = new Rule.Builder()
         .withName("view system resources")
         .withSecurityURI(uri)
         .withEffect(RuleEffect.ALLOW)
         .withFinalRule(true)
         // can't have both a rule on ownerId that looks for id and then "or" system as the or will have to be
         // independently evaluation.  Consider removing or functionality as it nullifies the and criteria
         .withAndFilterString("dataDomain.ownerId:system@b2bintegrator.com");

      r = b.build();
      this.addRule(header, r); */

    }

    /**
     * clears the rule base
     */
    public void clear() {
        rules.clear();
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
     * @param key the identity to get the rules for
     * @return an optional list of rules
     */
    public Optional<List<Rule>> rulesFor(@NotNull @Valid SecurityURIHeader key) {

        // return all the rules for this identity
        List<Rule> ruleList = rules.get(key.getIdentity());

        if (ruleList == null) {
            return Optional.empty();
        }

        return Optional.of(ruleList);
    }

    /**
     * This will execute a script with a given principal and resource context, it will return true or false
     * based upon the evaulation of the script.  The script is assumed to return a boolean value
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


    List<Rule> getApplicableRulesForPrincipalAndAssociatedRoles(PrincipalContext pcontext, ResourceContext rcontext) {
        // holder for the applicable rules
        List<Rule> applicableRules = new ArrayList<Rule>();
        // get the header for this pcontext and rcontext we are going to be comparing.
        SecurityURIHeader h = createHeaderFor(pcontext.getUserId(), rcontext);

        // find the rules that match this header
        Optional<List<Rule>> listop = rulesFor(h);

        if (listop.isPresent()) {
            applicableRules.addAll(listop.get());
        }

        // Add role rules
        for (String role : pcontext.getRoles()) {
            h = createHeaderFor(role, rcontext);
            listop = rulesFor(h);
            if (listop.isPresent()) {
                applicableRules.addAll(listop.get());
            }
        }

        if (!applicableRules.isEmpty()) {
            // Build SecurityURI
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
     * @param pcontext
     * @param rcontext
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
        SecurityCheckResponse response = new SecurityCheckResponse();
        // Record the pcontext and rcontext in the response
        response.setPrincipalContext(pcontext);
        response.setResourceContext(rcontext);
        // the final effect is defined by the defaultFinalEffect being passed in.
        // typically this will be DENY unless we have rules that allow it.
        // the rule base will have a default set in it.
        response.setFinalEffect(defaultFinalEffect);

        // Get the applicalble rules for this pcontext and rcontext this will include
        // the roles associated with the pcontext
        List<Rule> applicableRules = getApplicableRulesForPrincipalAndAssociatedRoles(pcontext, rcontext);

        // expand the set of uri's for this pcontext and rcontext
        List<SecurityURI> expanedUris = expandURIPrincipalIdentities(pcontext, rcontext);
        response.getApplicableSecurityURIs().addAll(expanedUris);
       // response.setUnexpandedSecurityURIs( would need the original urls prior to expansion unsure why we need that);

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
                Log.debug(" rule:" + r.getName() + "compared to uris:" + expanedUris.size());
            }

            // for each uri in the expanded set of uris which includes the principal userId and associated roles
            for (SecurityURI expanedPrincipalUri : expanedUris) {

                if (Log.isDebugEnabled()) {
                    Log.debug("Comparing:" + expanedPrincipalUri.toString());
                    Log.debug("To ruleName:" + r.getName() + " URI:" + r.getSecurityURI().toString());
                    Log.debug("");
                }

                // compare the uri to the rule uri to see if it matches ie. the rule is applicable
                if (WildCardMatcher.getInstance().wildcardMatch(expanedPrincipalUri.toString(), r.getSecurityURI().toString(),
                        IOCase.INSENSITIVE)) {
                    // the rule is applicable.  Check the precondition and post conditions scripts
                    RuleResult result = new RuleResult(r);
                    MatchEvent matchEvent =
                            MatchEvent.builder()
                                    .principalUriString(expanedPrincipalUri.toString())
                                    .ruleUriString(r.getSecurityURI().toString())
                                    .ruleName(r.getName())
                                    .matched(true)
                                    .difference(StringUtils.difference(expanedPrincipalUri.toString(), r.getSecurityURI().toString()))
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
                    String difference = StringUtils.difference(expanedPrincipalUri.toString(), r.getSecurityURI().toString());
                    response.getMatchEvents().add(
                            MatchEvent.builder()
                                    .principalUriString(expanedPrincipalUri.toString())
                                    .ruleUriString(r.getSecurityURI().toString())
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

        if (rcontext.getResourceId().isPresent()) {
            buri.withResourceId(rcontext.getResourceId().get());
        }

        SecurityURIHeader header = huri.build();
        SecurityURIBody body = buri.build();

        return new SecurityURI(header, body);
    }

    /**
     * Retrieve a list of filters that match a given principal ( and the associated roles ) for a given resource context
     *
     * @param ifilters existing filters that may already have been applied from prior rules
     * @param pcontext the principal context
     * @param rcontext the resource context
     * @return the list containing now what ever was passed in with ifilters and any addiitional rules to apply

    public List<Filter> getFilters(List<Filter> ifilters, @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext, @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext) {

        Set<Filter> filters = new HashSet<Filter>();
        filters.addAll(ifilters);

        // Find applicable uris to search for based upon the principal and resource.  This will expand
        // the set of uris to include roles of the associated principal
        List<SecurityURI> uris = this.expandURIPrincipalIdentities(pcontext, rcontext);

        // For each url,
        for (SecurityURI uri : uris) {
            // Find the rules that match the header of the uri
            Optional<List<Rule>> orules = this.rulesFor(uri.getHeader());

            boolean done = false;
            if (orules.isPresent()) {

                // allocate an array list for and and or filters
                // TODO we should only create these if we need them
                List<Filter> andFilters = new ArrayList<>();
                List<Filter> orFilters = new ArrayList<>();

                // Create a map of variables we  can pass to scripts.

                Map<String, String> variables = MorphiaUtils.createStandardVariableMapFrom(pcontext, rcontext);
                variables.put("identity", uri.getHeader().getIdentity());
                StringSubstitutor sub = new StringSubstitutor(variables);
                List<Rule> rules = orules.get();
                for (Rule rule : rules) {
                        if (rule.getAndFilterString() != null && !rule.getAndFilterString().isEmpty()) {
                            andFilters.add(MorphiaUtils.convertToFilter(rule.getAndFilterString(), variables, sub));
                        }

                    if (rule.getOrFilterString() != null && !rule.getOrFilterString().isEmpty()) {
                        orFilters.add(MorphiaUtils.convertToFilter(rule.getOrFilterString(), variables, sub));
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
                        done = true;
                        break;
                    }
                } ;
            }
            if (done) {
                break;
            }
        } ;

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
    */

    public List<Filter> getFilters(List<Filter> ifilters, @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext, @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext) {
        Set<Filter> filters = new HashSet<Filter>();
        filters.addAll(ifilters);

        SecurityCheckResponse response = this.checkRules(pcontext, rcontext);

        List<Filter> andFilters = new ArrayList<>();
        List<Filter> orFilters = new ArrayList<>();
        Map<String, String> variables = MorphiaUtils.createStandardVariableMapFrom(pcontext, rcontext);
        StringSubstitutor sub = new StringSubstitutor(variables);

        for (RuleResult result : response.getMatchedRuleResults()) {
            if (result.getDeterminedEffect() == RuleDeterminedEffect.NOT_APPLICABLE) {
                continue;
            }
            Rule rule = result.getRule();
            if (rule.getAndFilterString() != null && !rule.getAndFilterString().isEmpty()) {
                andFilters.add(MorphiaUtils.convertToFilter(rule.getAndFilterString(), variables, sub));
            }

            if (rule.getOrFilterString() != null && !rule.getOrFilterString().isEmpty()) {
                orFilters.add(MorphiaUtils.convertToFilter(rule.getOrFilterString(), variables, sub));
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
        //TODO: add a map override concept where by for a given principal id, and area.functionalDomain it will resolve to a certain realm
        // for now return what ever realm the user is in.
        // if (resourceContext != null && resourceContext.getFunctionalDomain().equals("security") && (resourceContext.getAction().equals("login") ))
        //    return DefaultRealm;

        if (principalContext != null)
            return principalContext.getDefaultRealm();
        else
            return DefaultRealm;
    }
}
