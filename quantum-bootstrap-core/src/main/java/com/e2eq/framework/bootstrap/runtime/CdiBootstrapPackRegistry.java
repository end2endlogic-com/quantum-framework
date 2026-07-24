package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.spi.BootstrapPackContributor;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRegistry;
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
public class CdiBootstrapPackRegistry implements BootstrapPackRegistry {

    @Inject
    Instance<BootstrapPackContributor> contributors;

    public CdiBootstrapPackRegistry() {
    }

    public CdiBootstrapPackRegistry(Iterable<BootstrapPackContributor> contributors) {
        this.contributors = new SimpleIterableInstance<>(contributors);
    }

    @Override
    public Optional<BootstrapPackDefinition> find(String packRef) {
        if (packRef == null || packRef.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(indexPacks().get(packRef));
    }

    @Override
    public List<BootstrapPackDefinition> list() {
        return new ArrayList<>(indexPacks().values());
    }

    private Map<String, BootstrapPackDefinition> indexPacks() {
        List<BootstrapPackContributor> ordered = new ArrayList<>();
        contributors.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(BootstrapPackContributor::priority));

        Map<String, BootstrapPackDefinition> indexed = new LinkedHashMap<>();
        for (BootstrapPackContributor contributor : ordered) {
            if (contributor == null || contributor.bootstrapPacks() == null) {
                continue;
            }
            contributor.bootstrapPacks().stream()
                    .filter(pack -> pack != null && pack.packRef() != null && !pack.packRef().isBlank())
                    .forEach(pack -> indexed.put(pack.packRef(), pack));
        }
        return indexed;
    }
}
