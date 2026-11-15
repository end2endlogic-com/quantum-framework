package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Whether this service owns the directory records it keeps in its system realm, as
 * {@link DirectoryRouting} (the one parser of the directory mode keys) decides. The realm catalog
 * and the memberships (realm memberships, user realm roles) are routed separately, because the
 * older catalog-only setting sends the catalog to the owner while memberships stay here.
 *
 * <p>Records held by a remote owner have no authority here. A write to the local copy is a second
 * copy that drifts from the owner's, so every write is refused with {@code DIRECTORY_REMOTE} (409):
 * the record must be written at the owner, through the remote directory client. Without
 * quantum-system on the classpath there is no remote directory, so everything is owned here.</p>
 */
@ApplicationScoped
public class DirectoryOwnership {

   @Inject
   Instance<DirectoryRouting> routings;

   private DirectoryRouting routing;

   @PostConstruct
   void init() {
      routing = routings != null && routings.isResolvable() ? routings.get() : null;
   }

   /** Package-private for tests: the routing this reads. */
   void routing(DirectoryRouting routing) {
      this.routing = routing;
   }

   /** True unless the realm catalog is held by a remote owner. */
   public boolean catalogOwnedHere() {
      return routing == null || !routing.catalogRemote();
   }

   /** True unless realm memberships and user realm roles are held by a remote owner. */
   public boolean membershipsOwnedHere() {
      return routing == null || !routing.membershipsRemote();
   }

   /** Refuses with {@code DIRECTORY_REMOTE} unless this service owns the realm catalog. */
   public void requireCatalogOwnedHere(String records) {
      if (!catalogOwnedHere()) {
         throw refused(records);
      }
   }

   /** Refuses with {@code DIRECTORY_REMOTE} unless this service owns the memberships. */
   public void requireMembershipsOwnedHere(String records) {
      if (!membershipsOwnedHere()) {
         throw refused(records);
      }
   }

   private static ApplicationAccessException refused(String records) {
      return new ApplicationAccessException(Code.DIRECTORY_REMOTE,
         records + " are held by the remote directory owner; write them there, not to this service's copy");
   }
}
