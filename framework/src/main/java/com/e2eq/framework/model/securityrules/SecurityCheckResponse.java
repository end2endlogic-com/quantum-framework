package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.security.Rule;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class SecurityCheckResponse {
   ResourceContext resourceContext;
   List<SecurityURI> requestSecurityURIs;
   PrincipalContext principalContext;
   RuleEffect finalEffect;
   List<SecurityURI> applicablePrincipalURIs = new ArrayList<>();
   List<Rule> evaluatedRules = new ArrayList<>();
   List<RuleResult> matchedRuleResults = new ArrayList<>();


   public RuleEffect getFinalEffect () {
      return finalEffect;
   }

   public void setFinalEffect (RuleEffect finalEffect) {
      this.finalEffect = finalEffect;
   }

   public List<SecurityURI> getApplicablePrincipalURIs () {
      return applicablePrincipalURIs;
   }

   public void setApplicablePrincipalURIs (List<SecurityURI> applicablePrincipalURIs) {
      this.applicablePrincipalURIs = applicablePrincipalURIs;
   }

   public List<RuleResult> getMatchedRuleResults () {
      return matchedRuleResults;
   }

   public void setMatchedRuleResults (List<RuleResult> matchedRuleResults) {
      this.matchedRuleResults = matchedRuleResults;
   }

   public List<Rule> getEvaluatedRules () {
      return evaluatedRules;
   }

   public void setEvaluatedRules (List<Rule> evaluatedRules) {
      this.evaluatedRules = evaluatedRules;
   }

   public ResourceContext getResourceContext () {
      return resourceContext;
   }

   public void setResourceContext (ResourceContext resourceContext) {
      this.resourceContext = resourceContext;
   }

   public PrincipalContext getPrincipalContext () {
      return principalContext;
   }

   public void setPrincipalContext (PrincipalContext principalContext) {
      this.principalContext = principalContext;
   }

   public List<SecurityURI> getRequestSecurityURIs () {
      return requestSecurityURIs;
   }

   public void setRequestSecurityURIs (List<SecurityURI> requestSecurityURIs) {
      this.requestSecurityURIs = requestSecurityURIs;
   }

   @Override
   public String toString () {
      return "SecurityCheckResponse{" +
                "resourceContext=" + resourceContext +
                ", requestSecurityURIs=" + requestSecurityURIs +
                ", principalContext=" + principalContext +
                ", finalEffect=" + finalEffect +
                ", applicablePrincipalURIs=" + applicablePrincipalURIs +
                ", evaluatedRules=" + evaluatedRules +
                ", matchedRuleResults=" + matchedRuleResults +
                '}';
   }
}
