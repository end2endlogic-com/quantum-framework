package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;

import java.util.List;
import java.util.Optional;

public interface BootstrapPackRegistry {
    Optional<BootstrapPackDefinition> find(String packRef);

    List<BootstrapPackDefinition> list();
}
