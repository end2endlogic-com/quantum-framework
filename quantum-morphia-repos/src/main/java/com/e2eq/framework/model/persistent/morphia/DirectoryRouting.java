package com.e2eq.framework.model.persistent.morphia;

/**
 * Where the directory is held: the realm catalog, and the memberships (realm memberships, user realm
 * roles, application bindings and installs). The one implementation is {@code QuantumModeConfig} in
 * quantum-system, the single parser of {@code quantum.directory.mode},
 * {@code quantum.system.directory.mode} and {@code quantum.mode}; this module only reads its answer.
 *
 * <p>The two usually move together. They differ only under the older catalog-only setting
 * ({@code quantum.system.directory.mode} with {@code quantum.directory.mode} unset), which routes
 * the realm catalog alone.</p>
 */
public interface DirectoryRouting {

   /** True when the memberships are held by the remote directory owner, not this service. */
   boolean membershipsRemote();

   /**
    * True when the realm catalog is held by the remote directory owner, not this service. A
    * routing that does not route the catalog on its own holds it with the memberships.
    */
   default boolean catalogRemote() {
      return membershipsRemote();
   }
}
