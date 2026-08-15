package com.e2eq.framework.rest.usage;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class UsageGovernanceTestObserver implements UsageObserver {

    private final CopyOnWriteArrayList<UsageObservation> observations = new CopyOnWriteArrayList<>();

    @Override
    public void observe(UsageObservation observation) {
        observations.add(observation);
    }

    public List<UsageObservation> observations() {
        return List.copyOf(observations);
    }

    public void reset() {
        observations.clear();
    }
}
