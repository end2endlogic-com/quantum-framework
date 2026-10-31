package com.e2eq.framework.rest.usage;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.rest.core.BaseResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Resolves the endpoint identity without using mutable request paths. */
@ApplicationScoped
public class UsageEndpointIdentityResolver {

    static final String MODEL_AREA = "model.functional.area";
    static final String MODEL_DOMAIN = "model.functional.domain";

    public UsageEndpointIdentity resolve(ResourceInfo resourceInfo, ContainerRequestContext requestContext) {
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null) {
            throw unavailable("JAX-RS resource method is unavailable");
        }

        Method method = resourceInfo.getResourceMethod();
        String area = property(requestContext, MODEL_AREA);
        String domain = property(requestContext, MODEL_DOMAIN);

        if (area == null || domain == null) {
            MappingValues mapping = staticMapping(resourceInfo.getResourceClass(), method);
            if (mapping != null) {
                area = mapping.area();
                domain = mapping.domain();
            }
        }
        if (area == null || domain == null) {
            throw unavailable("Endpoint has no trusted FunctionalMapping identity");
        }

        Operation operation = method.getAnnotation(Operation.class);
        String operationId = operation == null ? null : trimToNull(operation.operationId());
        if (operationId == null) {
            // SmallRye's method-based generated operation identity is stable for a stable resource contract.
            operationId = method.getName();
        }
        try {
            return new UsageEndpointIdentity(area, domain, operationId);
        } catch (IllegalArgumentException exception) {
            throw new UsageEnforcementStateException(
                    UsageEnforcementStateException.ENDPOINT_IDENTITY_UNAVAILABLE,
                    "Endpoint usage identity is invalid: " + exception.getMessage(),
                    exception);
        }
    }

    static UsageEndpointIdentity resolveStatic(Class<?> resourceClass, Method method) {
        MappingValues mapping = staticMapping(resourceClass, method);
        if (mapping == null) {
            throw unavailable("Endpoint has no trusted FunctionalMapping identity");
        }
        Operation operation = method.getAnnotation(Operation.class);
        String operationId = operation == null ? null : trimToNull(operation.operationId());
        return new UsageEndpointIdentity(
                mapping.area(), mapping.domain(), operationId == null ? method.getName() : operationId);
    }

    private static MappingValues staticMapping(Class<?> resourceClass, Method method) {
        FunctionalMapping mapping = method.getAnnotation(FunctionalMapping.class);
        if (mapping == null && resourceClass != null) {
            mapping = resourceClass.getAnnotation(FunctionalMapping.class);
        }
        if (mapping != null) {
            return new MappingValues(mapping.area(), mapping.domain());
        }

        Class<?> modelClass = findModelClass(resourceClass);
        if (modelClass == null) {
            return null;
        }
        mapping = modelClass.getAnnotation(FunctionalMapping.class);
        if (mapping != null) {
            return new MappingValues(mapping.area(), mapping.domain());
        }
        if (UnversionedBaseModel.class.isAssignableFrom(modelClass)) {
            try {
                UnversionedBaseModel model = (UnversionedBaseModel) modelClass.getDeclaredConstructor().newInstance();
                String area = trimToNull(model.bmFunctionalArea());
                String domain = trimToNull(model.bmFunctionalDomain());
                return area == null || domain == null ? null : new MappingValues(area, domain);
            } catch (ReflectiveOperationException exception) {
                throw new UsageEnforcementStateException(
                        UsageEnforcementStateException.ENDPOINT_IDENTITY_UNAVAILABLE,
                        "Unable to resolve BaseResource model FunctionalMapping for " + resourceClass.getName(),
                        exception);
            }
        }
        return null;
    }

    private static Class<?> findModelClass(Class<?> resourceClass) {
        Class<?> current = resourceClass;
        while (current != null && current != Object.class) {
            Type genericSuperclass = current.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> rawType
                    && BaseResource.class.isAssignableFrom(rawType)) {
                Type[] arguments = parameterized.getActualTypeArguments();
                if (arguments.length > 0 && arguments[0] instanceof Class<?> modelClass) {
                    return modelClass;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String property(ContainerRequestContext context, String name) {
        if (context == null) {
            return null;
        }
        Object value = context.getProperty(name);
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UsageEnforcementStateException unavailable(String message) {
        return new UsageEnforcementStateException(
                UsageEnforcementStateException.ENDPOINT_IDENTITY_UNAVAILABLE, message);
    }

    private record MappingValues(String area, String domain) {
    }
}
