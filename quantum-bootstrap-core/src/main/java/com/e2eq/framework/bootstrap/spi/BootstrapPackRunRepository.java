package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapPackRun;

import java.util.List;
import java.util.Optional;

public interface BootstrapPackRunRepository {
    /** Atomically create a run, or return the existing run for this exact execution key. */
    record Creation(BootstrapPackRun run, boolean created) { }

    default Creation createIfAbsent(BootstrapPackRun run) {
        throw new IllegalStateException("BOOTSTRAP_RUN_ATOMIC_CREATE_UNSUPPORTED");
    }

    default boolean isDurable() { return false; }

    BootstrapPackRun save(BootstrapPackRun run);

    Optional<BootstrapPackRun> find(String packRunRef);

    List<BootstrapPackRun> list();
}
