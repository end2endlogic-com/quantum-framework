package com.e2eq.framework.actionenablement.runtime;

import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;
import com.e2eq.framework.actionenablement.spi.ScopedActionRequirementContributor;
import com.e2eq.framework.actionenablement.spi.ScopedActionRequirementRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class CdiScopedActionRequirementRegistry implements ScopedActionRequirementRegistry {

    @Inject
    Instance<ScopedActionRequirementContributor> contributors;

    @Override
    public Optional<ScopedActionRequirement> find(ScopedActionRef ref) {
        if (ref == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(indexRequirements().get(ref.toUriString()));
    }

    @Override
    public List<ScopedActionRequirement> list() {
        return new ArrayList<>(indexRequirements().values());
    }

    private Map<String, ScopedActionRequirement> indexRequirements() {
        List<ScopedActionRequirementContributor> ordered = new ArrayList<>();
        contributors.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(ScopedActionRequirementContributor::priority));

        Map<String, ScopedActionRequirement> indexed = new LinkedHashMap<>();
        for (ScopedActionRequirementContributor contributor : ordered) {
            CollectionUtils.safeList(contributor.requirements()).stream()
                    .filter(requirement -> requirement != null && requirement.getScopedAction() != null)
                    .forEach(requirement -> indexed.put(requirement.getScopedAction().toUriString(), requirement));
        }
        return indexed;
    }
}
