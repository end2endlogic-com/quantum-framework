package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Whether this service owns the directory records (the realm catalog, realm memberships and user
 * realm roles) it keeps in its system realm, as {@link DirectoryRouting} (the one parser of
 * {@code quantum.directory.mode} and {@code quantum.mode}) decides.
 *
 * <p>Under a remote directory another service owns them. A local copy is then no authority, and a
 * write to it is a second copy that drifts from the owner's, so every write is refused with
 * {@code DIRECTORY_REMOTE} (409): the record must be written at the owner, through the remote
 * directory client. Without quantum-system on the classpath there is no remote directory, so the
 * records are owned here.</p>
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

   /** True unless the directory is held by a remote owner. */
   public boolean ownedHere() {
      return routing == null || !routing.membershipsRemote();
   }

   /** Refuses with {@code DIRECTORY_REMOTE} unless this service owns {@code records}. */
   public void requireOwnedHere(String records) {
      if (!ownedHere()) {
         throw new ApplicationAccessException(Code.DIRECTORY_REMOTE,
            records + " are held by the remote directory owner (quantum.directory.mode=remote);"
               + " write them there, not to this service's copy");
      }
   }
}
