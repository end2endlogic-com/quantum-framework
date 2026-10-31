package com.e2eq.framework.bootstrap.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BootstrapPackRun(
        String packRunRef,
        String packRef,
        String packVersion,
        String productRef,
        String profileRef,
        BootstrapPackApplyMode mode,
        BootstrapPackRunStatus status,
        Map<String, Object> scope,
        List<BootstrapPackStepRun> steps,
        Instant startedAt,
        Instant completedAt,
        String requestFingerprint,
        String packDefinitionJson,
        String runtimeArtifactSha256,
        long checkpointRevision
) {
    public BootstrapPackRun(String packRunRef,String packRef,String packVersion,String productRef,String profileRef,
            BootstrapPackApplyMode mode,BootstrapPackRunStatus status,Map<String,Object> scope,List<BootstrapPackStepRun> steps,
            Instant startedAt,Instant completedAt,String fingerprint,String definition,String runtime) {
        this(packRunRef,packRef,packVersion,productRef,profileRef,mode,status,scope,steps,startedAt,completedAt,fingerprint,definition,runtime,0);
    }
    public BootstrapPackRun {
        scope = scope == null ? Map.of() : Map.copyOf(scope);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public BootstrapPackRun(String packRunRef, String packRef, String packVersion,
            String productRef, String profileRef, BootstrapPackApplyMode mode,
            BootstrapPackRunStatus status, Map<String,Object> scope,
            List<BootstrapPackStepRun> steps, Instant startedAt, Instant completedAt) {
        this(packRunRef,packRef,packVersion,productRef,profileRef,mode,status,scope,steps,
            startedAt,completedAt,null,null,null);
    }
}
