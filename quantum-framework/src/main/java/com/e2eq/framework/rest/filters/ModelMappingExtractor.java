package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.rest.resources.BaseResource;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * Extracts functional mapping from the model class and injects it into the request context
 * for SecurityFilter to use. This makes the model class the single source of truth.
 * 
 * Priority is set to run before SecurityFilter (AUTHORIZATION - 100) so that the model
 * mapping is available when SecurityFilter determines the ResourceContext.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 100)
public class ModelMappingExtractor implements ContainerRequestFilter {
    
    @Context
    ResourceInfo resourceInfo;
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return;
        }
        
        try {
            Class<?> resourceClass = resourceInfo.getResourceClass();
            
            // Check if this is a BaseResource subclass
            if (BaseResource.class.isAssignableFrom(resourceClass)) {
                extractAndSetMapping(resourceClass, requestContext);
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Failed to extract model mapping: %s", e.getMessage());
            }
        }
    }
    
    /**
     * Extract functional mapping from model class.
     * Priority 1: @FunctionalMapping annotation on model class
     * Priority 2: bmFunctionalArea()/bmFunctionalDomain() methods on model instance
     */
    private void extractAndSetMapping(Class<?> resourceClass, ContainerRequestContext requestContext) {
        try {
            java.lang.reflect.Type genericSuperclass = resourceClass.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) genericSuperclass;
                java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    Class<?> modelClass = (Class<?>) typeArgs[0];
                    
                    // Priority 1: @FunctionalMapping annotation on model class
                    FunctionalMapping mapping = modelClass.getAnnotation(FunctionalMapping.class);
                    if (mapping != null) {
                        requestContext.setProperty("model.functional.area", mapping.area());
                        requestContext.setProperty("model.functional.domain", mapping.domain());
                        if (Log.isDebugEnabled()) {
                            Log.debugf("Extracted model @FunctionalMapping: area=%s, domain=%s from %s", 
                                mapping.area(), mapping.domain(), modelClass.getSimpleName());
                        }
                        return;
                    }
                    
                    // Priority 2: bmFunctionalArea()/bmFunctionalDomain() methods
                    if (UnversionedBaseModel.class.isAssignableFrom(modelClass)) {
                        try {
                            UnversionedBaseModel instance = (UnversionedBaseModel) modelClass.getDeclaredConstructor().newInstance();
                            String area = instance.bmFunctionalArea();
                            String domain = instance.bmFunctionalDomain();
                            
                            if (area != null && domain != null) {
                                requestContext.setProperty("model.functional.area", area);
                                requestContext.setProperty("model.functional.domain", domain);
                                if (Log.isDebugEnabled()) {
                                    Log.debugf("Extracted model bmFunctional methods: area=%s, domain=%s from %s", 
                                        area, domain, modelClass.getSimpleName());
                                }
                            }
                        } catch (Exception e) {
                            if (Log.isDebugEnabled()) {
                                Log.debugf("Could not call bmFunctional methods: %s", e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Error extracting model mapping: %s", e.getMessage());
            }
        }
    }
}
