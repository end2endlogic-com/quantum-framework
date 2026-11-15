package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.DirectoryOwnership;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.rest.resources.ApplicationAccessExceptionMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * The realm catalog, realm membership and user realm role endpoints when a remote directory owner
 * holds those records, as {@link DirectoryOwnership} reads it. The catalog ({@link RealmResource})
 * follows catalog ownership; memberships and user realm roles follow membership ownership, so the
 * older catalog-only setting closes the catalog endpoints alone.
 *
 * <p>For records the owner holds, this service's copy stopped changing when they went remote. Reading it would answer from a stale copy, and writing it would fork it (the repositories
 * refuse that too). So every endpoint of {@link RealmResource}, {@link RealmTenantMembershipResource}
 * and {@link UserRealmRoleResource} is refused with {@code DIRECTORY_REMOTE} (409), except the ones
 * that read and write at the owner through {@code RealmMembershipService}: the application and
 * tenant grant of a (user, realm) assignment. An endpoint not listed here is refused, so a new one
 * cannot quietly serve the local copy. Under a local directory nothing changes.</p>
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 10)
@IfBuildProperty(name = "quantum.system-rest.enabled", stringValue = "true", enableIfMissing = true)
public class DirectoryRemoteRestFilter implements ContainerRequestFilter {

   /** The directory resources. */
   static final List<Class<?>> DIRECTORY_RESOURCES =
      List.of(RealmResource.class, RealmTenantMembershipResource.class, UserRealmRoleResource.class);

   /** Endpoints served at the directory owner; they stay open under a remote directory. */
   static final Set<String> SERVED_BY_OWNER = Set.of(
      "UserRealmRoleResource#getApplicationGrant",
      "UserRealmRoleResource#setApplicationGrant",
      "UserRealmRoleResource#clearApplicationGrant",
      "UserRealmRoleResource#getTenantGrant",
      "UserRealmRoleResource#setTenantGrant");

   @Context
   ResourceInfo resourceInfo;

   @Inject
   DirectoryOwnership directoryOwnership;

   @Override
   public void filter(ContainerRequestContext requestContext) {
      if (directoryOwnership == null || resourceInfo == null) {
         return;
      }
      Class<?> resourceClass = resourceInfo.getResourceClass();
      if (ownedHere(resourceClass)) {
         return;
      }
      String refused = refusedEndpoint(resourceClass, resourceInfo.getResourceMethod());
      if (refused != null) {
         requestContext.abortWith(new ApplicationAccessExceptionMapper().toResponse(
            new ApplicationAccessException(ApplicationAccessException.Code.DIRECTORY_REMOTE,
               "these records are held by the remote directory owner;"
                  + " this service's copy is not served: use the owner's directory (" + refused + ")")));
      }
   }

   /** Whether this service owns the records the resource serves: the catalog, or the memberships. */
   private boolean ownedHere(Class<?> resourceClass) {
      if (resourceClass != null && RealmResource.class.isAssignableFrom(resourceClass)) {
         return directoryOwnership.catalogOwnedHere();
      }
      return directoryOwnership.membershipsOwnedHere();
   }

   /** The endpoint's name when it is a directory endpoint not served at the owner; null otherwise. */
   static String refusedEndpoint(Class<?> resourceClass, Method method) {
      if (resourceClass == null) {
         return null;
      }
      Class<?> directoryResource = DIRECTORY_RESOURCES.stream()
         .filter(type -> type.isAssignableFrom(resourceClass))
         .findFirst()
         .orElse(null);
      if (directoryResource == null) {
         return null;
      }
      String endpoint = directoryResource.getSimpleName() + "#" + (method == null ? "?" : method.getName());
      return SERVED_BY_OWNER.contains(endpoint) ? null : endpoint;
   }
}
