package com.e2eq.framework.actionenablement.spi;

import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;

import java.util.Collection;

public interface ScopedActionRequirementContributor {

    default int priority() {
        return 100;
    }

    Collection<ScopedActionRequirement> requirements();
}
