package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapPackRun;

import java.util.List;
import java.util.Optional;

public interface BootstrapPackRunRepository {
    BootstrapPackRun save(BootstrapPackRun run);

    Optional<BootstrapPackRun> find(String packRunRef);

    List<BootstrapPackRun> list();
}
