package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;

import java.util.Collection;

public interface BootstrapPackContributor {

    default int priority() {
        return 100;
    }

    Collection<BootstrapPackDefinition> bootstrapPacks();
}
