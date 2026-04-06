package com.e2eq.framework.actionenablement.spi;

import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;

import java.util.List;
import java.util.Optional;

public interface ScopedActionRequirementRegistry {

    Optional<ScopedActionRequirement> find(ScopedActionRef ref);

    List<ScopedActionRequirement> list();
}
