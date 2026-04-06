package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRunRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class InMemoryBootstrapPackRunRepository implements BootstrapPackRunRepository {

    private final Map<String, BootstrapPackRun> runs = new LinkedHashMap<>();

    @Override
    public synchronized BootstrapPackRun save(BootstrapPackRun run) {
        runs.put(run.packRunRef(), run);
        return run;
    }

    @Override
    public synchronized Optional<BootstrapPackRun> find(String packRunRef) {
        return Optional.ofNullable(runs.get(packRunRef));
    }

    @Override
    public synchronized List<BootstrapPackRun> list() {
        return new ArrayList<>(runs.values());
    }
}
