package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRunRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit test/embedded fixture; never selected implicitly by CDI. */
public class InMemoryBootstrapPackRunRepository implements BootstrapPackRunRepository {

    private final Map<String, BootstrapPackRun> runs = new LinkedHashMap<>();

    @Override
    public synchronized Creation createIfAbsent(BootstrapPackRun run) {
        var current=runs.get(run.packRunRef());
        if(current!=null)return new Creation(current,false);
        runs.put(run.packRunRef(),run);
        return new Creation(run,true);
    }

    @Override public synchronized BootstrapPackRun compareAndSet(BootstrapPackRun expected,BootstrapPackRun updated) {
        if (!java.util.Objects.equals(runs.get(expected.packRunRef()),expected))
            throw new IllegalStateException("BOOTSTRAP_CHECKPOINT_CONFLICT");
        return save(updated);
    }
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
