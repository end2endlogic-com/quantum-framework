package com.e2eq.framework.rest.filters;

import com.e2eq.framework.securityrules.RuleContext;

import com.e2eq.framework.rest.filters.inactive.PermissionPreFilter;
import io.quarkus.logging.Log;
import io.smallrye.jwt.auth.principal.JWTParser;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

@Provider
public class PermissionDynamicFeature implements DynamicFeature {

   @Inject
   RuleContext ruleContext;

   @Inject
   JWTParser parser;

   @Override
   public void configure (ResourceInfo resourceInfo, FeatureContext context) {

      PermissionCheck checkAnnotation = resourceInfo.getResourceMethod().getAnnotation(PermissionCheck.class);
      if (checkAnnotation == null) return;
      if (Log.isDebugEnabled())
         Log.debug(">> Adding dynamic feature to method:" + resourceInfo.getResourceClass().getSimpleName() + ":" + resourceInfo.getResourceMethod());
      PermissionPreFilter filter = new PermissionPreFilter( ruleContext, parser, checkAnnotation.area(),
         checkAnnotation.functionalDomain(),
         checkAnnotation.action());
      context.register(filter);
      if (Log.isDebugEnabled())
         Log.debug("Added permission check to method:" + resourceInfo.getResourceClass() + ":" + resourceInfo.getResourceMethod());
   }
}
