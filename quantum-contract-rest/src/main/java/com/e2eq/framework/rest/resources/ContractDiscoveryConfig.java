package com.e2eq.framework.rest.resources;

import io.smallrye.config.ConfigMapping;
import java.util.Map;
import java.util.Optional;

/** Opt-in, immutable producer snapshots. No snapshots changes the existing discovery behavior. */
@ConfigMapping(prefix = "quantum.contract.discovery")
public interface ContractDiscoveryConfig {
    /** Version selected without a selector; absent preserves existing discovery. */
    Optional<String> defaultVersion();
    /** Named immutable producer snapshots. */
    Map<String, Snapshot> snapshots();
    interface Snapshot {
        /** Exact contract version declared by the snapshot document. */
        String version();
        /** Server-controlled file or classpath location; empty means undeclared. */
        Optional<String> location();
    }
}
