package com.e2eq.framework.model.persistent.morphia;

/**
 * Where the directory (realm catalog, memberships, application bindings and installs) is held.
 * The one implementation is {@code QuantumModeConfig} in quantum-system, the single parser of
 * {@code quantum.directory.mode} and {@code quantum.mode}; this module only reads its answer.
 */
public interface DirectoryRouting {

   /** True when the directory is held by the remote directory owner, not this service. */
   boolean membershipsRemote();
}
