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
   // identity, list of rules
   Map<String, List<Rule>> rules = new HashMap<>();

   public static String DefaultRealm = "system-com";

   public RuleContext() {
      // load rules
      //TODO: Need to understand how to determine tenant, account etc on initialization
      Log.debug("Creating ruleContext");
   }

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

   protected void addSystemRules() {
      // add default rules for the system
      // first explicitly add the "system"
      // to operate with in the security area
      SecurityURI suri = new SecurityURI(SecurityUtils.systemSecurityHeader,SecurityUtils.systemSecurityBody);

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
      this.addRule(header,r);

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
      this.addRule(header,r);


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

   public void clear() {
      rules.clear();
   }

   public void addRule (@NotNull @Valid SecurityURIHeader key, @Valid @NotNull Rule rule) {
      // Store rules by identity
      List<Rule> list = rules.get(key.getIdentity());

      if (list == null) {
         list = new ArrayList<Rule>();
         rules.put(key.getIdentity(), list);
      }

      list.add(rule);

   }

   public Optional<List<Rule>> rulesFor (@NotNull @Valid SecurityURIHeader key) {

      // return all the rules for this identity
      List<Rule> ruleList = rules.get(key.getIdentity());

      if (ruleList == null) {
         return Optional.empty();
      }

      return Optional.of(ruleList);
   }

   boolean checkRule (PrincipalContext pcontext, ResourceContext rcontext, String rule) {
     // Context c = Context.newBuilder().allowAllAccess(true).build();
      Context c = Context.newBuilder().allowAllAccess(true).build();
      c.getBindings("js").putMember("pcontext", pcontext);
      c.getBindings("js").putMember("rcontext", rcontext);

      boolean allow = c.eval("js", rule).asBoolean();
      return allow;

   }

   SecurityURIHeader getHeaderFor(String identity, ResourceContext rcontext) {
      // Add principal rules
     return new SecurityURIHeader.Builder()
         .withIdentity(identity)
         .withArea(rcontext.getArea())
         .withFunctionalDomain(rcontext.getFunctionalDomain())
         .withAction(rcontext.getAction())
         .build();
   }

   public SecurityCheckResponse check (@Valid @NotNull PrincipalContext pcontext, @Valid @NotNull ResourceContext rcontext) {

      if (Log.isDebugEnabled()) {
         Log.debug("####  checking Permissions for pcontext:" + pcontext.toString() + " resource context:"  + rcontext.toString());
      }

      // Create a response to show how we came to the conclusion
      SecurityCheckResponse response = new SecurityCheckResponse();
      // Record the pcontext and rcontext in the response
      response.setPrincipalContext(pcontext);
      response.setResourceContext(rcontext);
      // by default deny all
      response.setFinalEffect(RuleEffect.DENY);

      // holder for the applicable rules
      List<Rule> applicableRules = new ArrayList<Rule>();

      // get the header for this pcontext and rcontext we are going to be comparing.
      SecurityURIHeader h = getHeaderFor(pcontext.getUserId(), rcontext);

      // find the rules that match this header
      Optional<List<Rule>> listop = rulesFor(h);

      if (listop.isPresent()) {
         applicableRules.addAll(listop.get());
      }

      // Add role rules
      for (String role : pcontext.getRoles()) {
         h = getHeaderFor(role, rcontext);
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
               public int compare (Rule r1, Rule r2) {
                  return r1.getPriority() - r2.getPriority();
               }
            };
            applicableRules.sort(orderingByPriority);
         }
         List<SecurityURI> uris = buildFromContext(pcontext, rcontext);
         response.getApplicablePrincipalURIs().addAll(uris);
         response.setRequestSecurityURIs(uris);

         if (Log.isDebugEnabled()) {
            Log.debug("");
            Log.debug("--- Applicable rules:" + applicableRules.size());
         }

         boolean complete = false;

         for (Rule r : applicableRules) {
            response.getEvaluatedRules().add(r);

            if (Log.isDebugEnabled()) {
               Log.debug(" rule:" + r.getName() + "compared to uris:" + uris.size());
            }
            for (SecurityURI uri : uris) {

               if (Log.isDebugEnabled()) {
                  Log.debug("Comparing:" + uri.toString());
                  Log.debug("To ruleName:" + r.getName() + " URI:" + r.getSecurityURI().toString());
                  Log.debug("");
               }
               if (WildCardMatcher.getInstance().wildcardMatch(uri.toString(), r.getSecurityURI().toString(),
                  IOCase.INSENSITIVE)) {
                  RuleResult result = new RuleResult(r);

                  if (r.getPostconditionScript() != null) {
                     if (checkRule(pcontext, rcontext, r.getPostconditionScript())) {
                        result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                        response.setFinalEffect(r.getEffect());
                     }
                     else {
                        result.setDeterminedEffect(RuleDeterminedEffect.NOT_APPLICABLE);
                        //response.setFinalEffect(r.getEffect());
                     }
                  }
                  else {
                     result.setDeterminedEffect(RuleDeterminedEffect.valueOf(r.getEffect()));
                     response.setFinalEffect(r.getEffect());
                  }

                  response.getMatchedRuleResults().add(result);

                  if (r.isFinalRule()) {
                    complete = true;
                    break;
                  }
               } else {
                  if (Log.isDebugEnabled()) {
                     String difference = StringUtils.difference(uri.toString(), r.getSecurityURI().toString() );
                     Log.debug(" >>>  Difference:" + difference);
                  }
               }

            }
            if (Log.isDebugEnabled()) {
               Log.debug("");
               Log.debug(" -- Matched Rules:" );
               for (RuleResult result : response.getMatchedRuleResults()) {
                  Log.debug("  " + result.getRule().getName() + " " + result.getDeterminedEffect());
               }
            }
            if (complete) {
               break;
            }
         }
      }
      return response;
   }


   List<SecurityURI> buildFromContext (@NotNull @Valid PrincipalContext pcontext, @NotNull @Valid ResourceContext rcontext) {
      List<SecurityURI> uris = new ArrayList<SecurityURI>();

      // Add the roles first because they probably will resolve first
      for (String role : pcontext.getRoles()) {
         uris.add(buildFromContext(role, pcontext, rcontext));
      }

      // Add the principal if role based rules don't work see if its explicitly defined
      uris.add( buildFromContext(pcontext.getUserId(), pcontext, rcontext) );

      return uris;
   }

   SecurityURI buildFromContext (@NotNull String principalId, @NotNull @Valid PrincipalContext pcontext, @NotNull @Valid ResourceContext rcontext) {

      SecurityURIHeader.Builder huri = new SecurityURIHeader.Builder()
         .withIdentity(principalId)
         .withArea(rcontext.getArea())
         .withFunctionalDomain(rcontext.getFunctionalDomain())
         .withAction(rcontext.getAction());


      SecurityURIBody.Builder buri = new SecurityURIBody.Builder()
         .withRealm(pcontext.getDefaultRealm())
         .withOrgRefName(pcontext.getDataDomain().getOrgRefName())
         .withAccountNumber(pcontext.getDataDomain().getAccountNum())
         .withTenantId(pcontext.getDataDomain().getTenantId())
         .withOwnerId(pcontext.getUserId())
         .withDataSegment(Integer.toString(pcontext.getDataDomain().getDataSegment()));

      if (rcontext.getResourceId().isPresent()) {
         buri.withResourceId(rcontext.getResourceId().get());
      }

      SecurityURIHeader header = huri.build();
      SecurityURIBody body = buri.build();

      return new SecurityURI(header, body);
   }

   public List<Filter> getFilters (List<Filter> filters, @Valid @NotNull( message="Principal Context can not be null" ) PrincipalContext pcontext, @Valid @NotNull (message="Resource Context can not be null") ResourceContext rcontext) {

      // Find applicable rules
     List<SecurityURI> uris =  this.buildFromContext(pcontext, rcontext);

      uris.forEach((uri) -> {
         Optional<List<Rule>> orules = this.rulesFor(uri.getHeader());
         List<Filter> andFilters = new ArrayList<>();
         List<Filter> orFilters = new ArrayList<>();
         if (orules.isPresent()) {
            Map<String, String> variables = MorphiaUtils.createVariableMapFrom(pcontext, rcontext);
            StringSubstitutor sub = new StringSubstitutor(variables);
            List<Rule> rules = orules.get();
            rules.forEach((rule) -> {
               if (rule.getAndFilterString() != null && !rule.getAndFilterString().isEmpty()) {
                  andFilters.add(MorphiaUtils.convertToFilter(rule.getAndFilterString(), variables, sub));
               }

               if (rule.getOrFilterString() != null&& !rule.getOrFilterString().isEmpty()) {
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
                     filters.add(Filters.and(orFilters.toArray( new Filter[orFilters.size()])));
                  }

                  //Filter andFilter = Filters.and(andFilters.toArray(new Filter[andFilters.size()]));
                  //Filter orFilter = Filters.or(orFilters.toArray(new Filter[orFilters.size()]));
                  /* switch ( joinOp) {
                     case AND:
                        filters.add(Filters.and(andFilter, orFilter));
                        break;
                     case OR:
                        filters.add(Filters.or(andFilter, orFilter));
                        break;
                     default:
                        throw new UnsupportedOperationException("Operation not support for joinOp:");
                  }
                  */
               }  else {
                  if (!andFilters.isEmpty()) {
                     filters.addAll(andFilters);
                     andFilters.clear();
                  }
                  else {
                     if (!orFilters.isEmpty()) {
                        filters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
                        orFilters.clear();
                     }
                  }
               }
            });
         }
      });

      return filters;
   }

   public String getRealmId (PrincipalContext principalContext, ResourceContext resourceContext) {
      //TODO: add a map override concept where by for a given principal id, and area.functionalDomain it will resolve to a certain realm
      // for now return what ever realm the user is in.
      // if (resourceContext != null && resourceContext.getFunctionalDomain().equals("security") && (resourceContext.getAction().equals("login") ))
      //    return DefaultRealm;

      if ( principalContext != null)
       return principalContext.getDefaultRealm();
      else
         return DefaultRealm;
   }
}
