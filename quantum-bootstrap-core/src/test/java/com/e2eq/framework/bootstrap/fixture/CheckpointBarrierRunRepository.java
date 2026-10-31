package com.e2eq.framework.bootstrap.fixture;

/** Packaged only in the explicitly selected journey-fixture test artifact. */
@jakarta.enterprise.inject.Alternative @jakarta.annotation.Priority(1)
@jakarta.enterprise.context.ApplicationScoped
@io.quarkus.arc.properties.IfBuildProperty(name="quantum.bootstrap.journey-fixture.enabled",stringValue="true")
public class CheckpointBarrierRunRepository extends com.e2eq.framework.bootstrap.runtime.MongoBootstrapPackRunRepository {
    @Override public com.e2eq.framework.bootstrap.model.BootstrapPackRun compareAndSet(
        com.e2eq.framework.bootstrap.model.BootstrapPackRun expected,
        com.e2eq.framework.bootstrap.model.BootstrapPackRun updated) {
        String target=System.getProperty("journey.pause-step");
        if (target!=null && !updated.steps().isEmpty()) {
            var step=updated.steps().get(updated.steps().size()-1);
            if (target.equals(step.stepRef()) && step.status()==com.e2eq.framework.bootstrap.model.BootstrapStepStatus.COMPLETED) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("journey.marker-path")),expected.packRunRef());
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch(java.io.IOException | InterruptedException e) { throw new IllegalStateException("JOURNEY_BARRIER_FAILED",e); }
            }
        }
        return super.compareAndSet(expected,updated);
    }
}
