package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.bootstrap.model.BootstrapPackStepDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackStepRun;
import com.e2eq.framework.bootstrap.model.BootstrapStepOutcome;
import com.e2eq.framework.bootstrap.model.BootstrapStepRequest;
import com.e2eq.framework.bootstrap.model.BootstrapStepResult;
import com.e2eq.framework.bootstrap.model.BootstrapStepStatus;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRegistry;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRunRepository;
import com.e2eq.framework.bootstrap.spi.BootstrapPackStepHandler;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class BootstrapPackService {

    private final BootstrapPackRegistry registry;
    private final BootstrapPackRunRepository runRepository;
    private final Iterable<BootstrapPackStepHandler> handlers;

    @Inject
    public BootstrapPackService(
        BootstrapPackRegistry registry,
        Instance<BootstrapPackStepHandler> handlers,
        BootstrapPackRunRepository runRepository
    ) {
        this(registry, toOrderedHandlers(handlers), runRepository);
    }

    public BootstrapPackService(
        BootstrapPackRegistry registry,
        Iterable<BootstrapPackStepHandler> handlers,
        BootstrapPackRunRepository runRepository
    ) {
        this.registry = registry;
        this.handlers = handlers;
        this.runRepository = runRepository;
    }

    public List<BootstrapPackDefinition> listPacks() {
        return registry.list();
    }

    public List<BootstrapPackRun> listRuns() {
        return runRepository.list();
    }

    public java.util.Optional<BootstrapPackRun> findRun(String executionRef) {
        return runRepository.find(executionRef);
    }

    public BootstrapPackRun apply(ApplyBootstrapPackRequest request) {
        return applyRun(request, buildRunRef(), false);
    }

    /** A duplicate key observes its run, including FAILED/RUNNING; it never starts recovery. */
    public BootstrapPackRun apply(ApplyBootstrapPackRequest request, String executionRef) {
        if (executionRef == null || !executionRef.matches("[A-Za-z0-9_-]{16,128}"))
            throw new IllegalArgumentException("BOOTSTRAP_EXECUTION_KEY_INVALID");
        if (!runRepository.isDurable()) throw new IllegalStateException("BOOTSTRAP_DURABLE_RUN_STORE_REQUIRED");
        return applyRun(request, executionRef, true);
    }

    private BootstrapPackRun applyRun(ApplyBootstrapPackRequest input, String executionRef, boolean keyed) {
        ApplyBootstrapPackRequest request = BootstrapRunIdentity.copy(input, ApplyBootstrapPackRequest.class);
        String fingerprint = BootstrapRunIdentity.fingerprint(request);
        var existing = runRepository.find(executionRef);
        if (existing.isPresent()) return matching(existing.get(), fingerprint);
        String runtime = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue("quantum.provisioning.runtime-artifact-sha256", String.class).orElse(null);
        if (keyed && (runtime == null || !runtime.matches("[0-9a-f]{64}")))
            throw new IllegalStateException("BOOTSTRAP_RUNTIME_IDENTITY_REQUIRED");
        BootstrapPackDefinition pack = BootstrapRunIdentity.copy(registry.find(request.packRef())
            .orElseThrow(() -> new IllegalArgumentException("Unknown bootstrap pack: " + request.packRef())),
            BootstrapPackDefinition.class);
        validatePinnedBinding(request, pack, runtime);
        Instant started = Instant.now();
        Map<String,Object> scope = buildScope(request, pack);
        pack = preparePack(pack, request, scope);
        String definition = BootstrapRunIdentity.json(pack);
        BootstrapPackRun initial = new BootstrapPackRun(executionRef, pack.packRef(), pack.packVersion(),
            pack.productRef(), pack.profileRef(), request.mode(), BootstrapPackRunStatus.RUNNING,
            scope, List.of(), started, null, fingerprint, definition, runtime);
        var creation = runRepository.createIfAbsent(initial);
        if (!creation.created()) return matching(creation.run(),fingerprint);
        return executeRemaining(initial,pack,request);
    }

    private BootstrapPackRun executeRemaining(BootstrapPackRun initial,BootstrapPackDefinition pack,ApplyBootstrapPackRequest request) {
        BootstrapPackRun current=initial;
        List<BootstrapPackStepRun> steps=new ArrayList<>(current.steps());
        Map<String,BootstrapPackStepRun> completed=new LinkedHashMap<>();
        steps.forEach(step -> completed.put(step.stepRef(),step));
        for (BootstrapPackStepDefinition step:pack.steps()) {
            if (completed.containsKey(step.stepRef())) continue;
            steps.add(new BootstrapPackStepRun(step.stepRef(),BootstrapStepStatus.RUNNING,BootstrapStepOutcome.SKIPPED,
                Map.of("reason","EFFECT_INTENT_PERSISTED"),Instant.now(),null));
            current=runRepository.compareAndSet(current,checkpoint(current,steps,BootstrapPackRunStatus.RUNNING));
            BootstrapPackStepRun result=executeStep(pack,request,current.scope(),step,completed);
            steps.set(steps.size()-1,result); completed.put(step.stepRef(),result);
            boolean failed=result.status()!=BootstrapStepStatus.COMPLETED;
            current=runRepository.compareAndSet(current,checkpoint(current,steps,failed?BootstrapPackRunStatus.FAILED:BootstrapPackRunStatus.RUNNING));
            if (failed) return current;
        }
        return runRepository.compareAndSet(current,checkpoint(current,steps,BootstrapPackRunStatus.COMPLETED));
    }

    /** Operator initiated reconciliation. No rediscovery, lease expiry, or automatic retries. */
    public BootstrapPackRun recover(String key,long expectedRevision,String actor) {
        if (!runRepository.isDurable()) throw new IllegalStateException("BOOTSTRAP_DURABLE_RUN_STORE_REQUIRED");
        BootstrapPackRun run=runRepository.find(key).orElseThrow(() -> new IllegalStateException("BOOTSTRAP_RUN_NOT_FOUND"));
        if (run.checkpointRevision()!=expectedRevision) throw new IllegalStateException("BOOTSTRAP_CHECKPOINT_CONFLICT");
        if (run.status()==BootstrapPackRunStatus.COMPLETED) return run;
        String runtime=org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue("quantum.provisioning.runtime-artifact-sha256",String.class).orElse(null);
        if (!java.util.Objects.equals(runtime,run.runtimeArtifactSha256())) throw new IllegalStateException("BOOTSTRAP_RECOVERY_RUNTIME_DRIFT");
        if (!(run.scope().get("actorRef") instanceof String originalActor) || run.packDefinitionJson()==null)
            throw new IllegalStateException("BOOTSTRAP_RECOVERY_UNPINNED");
        BootstrapPackDefinition pack;
        try { pack=new com.fasterxml.jackson.databind.ObjectMapper().readValue(run.packDefinitionJson(),BootstrapPackDefinition.class); }
        catch(Exception e) { throw new IllegalStateException("BOOTSTRAP_RECOVERY_PLAN_INVALID",e); }
        var scope=new LinkedHashMap<String,Object>(run.scope());
        for (String field:List.of("packRef","packVersion","productRef","profileRef","environmentRef","realmRef","tenantRef","workspaceRef","actorRef")) scope.remove(field);
        var request=new ApplyBootstrapPackRequest(run.packRef(),run.mode(),run.productRef(),(String)run.scope().get("environmentRef"),
            (String)run.scope().get("realmRef"),(String)run.scope().get("tenantRef"),(String)run.scope().get("workspaceRef"),originalActor,scope);
        matching(run,BootstrapRunIdentity.fingerprint(request));
        List<BootstrapPackStepRun> reconciled=new ArrayList<>();
        for (BootstrapPackStepRun prior:run.steps()) {
            if (prior.status()==BootstrapStepStatus.COMPLETED) { reconciled.add(prior); continue; }
            var step=pack.steps().stream().filter(s -> s.stepRef().equals(prior.stepRef())).findFirst().orElseThrow(() -> new IllegalStateException("BOOTSTRAP_RECOVERY_PLAN_INVALID"));
            var handler=resolveHandler(step);
            if (handler==null) throw new IllegalStateException("BOOTSTRAP_RECOVERY_UNSUPPORTED");
            BootstrapStepResult proof;
            try (var ignored=SecurityCallScope.openIgnoringRules()) {
                proof=handler.reconcile(new BootstrapStepRequest(pack.packRef(),pack.packVersion(),pack.productRef(),pack.profileRef(),
                    step.stepRef(),step.kind(),run.mode(),originalActor,run.scope(),step.config()));
            }
            if (proof==null || proof.status()!=BootstrapStepStatus.COMPLETED) throw new IllegalStateException("BOOTSTRAP_RECOVERY_EFFECT_UNCERTAIN");
            var details=new LinkedHashMap<>(proof.details()); details.put("reconciledBy",actor);
            reconciled.add(new BootstrapPackStepRun(step.stepRef(),proof.status(),proof.outcome(),details,prior.startedAt(),Instant.now()));
        }
        // This optimistic claim also fences any original executor's next intent/checkpoint.
        BootstrapPackRun claimed=runRepository.compareAndSet(run,checkpoint(run,reconciled,BootstrapPackRunStatus.RUNNING));
        return executeRemaining(claimed,pack,request);
    }

    private BootstrapPackDefinition preparePack(BootstrapPackDefinition pack, ApplyBootstrapPackRequest request,
                                                Map<String, Object> scope) {
        List<BootstrapPackStepDefinition> prepared = new ArrayList<>();
        for (BootstrapPackStepDefinition step : pack.steps()) {
            BootstrapPackStepHandler handler = resolveHandler(step);
            // Existing generic packs retain their explicit NO_HANDLER failure receipt.
            Map<String, Object> config = handler == null ? step.config() : handler.prepare(new BootstrapStepRequest(
                pack.packRef(), pack.packVersion(), pack.productRef(), pack.profileRef(), step.stepRef(), step.kind(),
                request.mode(), request.actorRef(), scope, step.config()));
            if (config == null) throw new IllegalStateException("BOOTSTRAP_PREPARED_CONFIG_REQUIRED");
            prepared.add(new BootstrapPackStepDefinition(step.stepRef(), step.description(), step.kind(),
                step.applyPolicy(), step.dependsOn(), config));
        }
        return BootstrapRunIdentity.copy(new BootstrapPackDefinition(pack.packRef(), pack.packVersion(),
            pack.productRef(), pack.profileRef(), prepared), BootstrapPackDefinition.class);
    }

    /** Validate the same frozen definition that will be persisted, before any effect. */
    private void validatePinnedBinding(ApplyBootstrapPackRequest request, BootstrapPackDefinition pack, String runtime) {
        Map<String, Object> pins = request.scopeAttributes();
        if (pins.containsKey("expectedPackVersion") || pins.containsKey("expectedPackSha256")
                || pins.containsKey("expectedRuntimeArtifactSha256")) {
            if (!java.util.Objects.equals(runtime, pins.get("expectedRuntimeArtifactSha256")))
                throw new IllegalStateException("BOOTSTRAP_RUNTIME_DRIFT");
            if (!pack.packVersion().equals(pins.get("expectedPackVersion"))
                    || !BootstrapRunIdentity.fingerprint(pack).equals(pins.get("expectedPackSha256"))
                    || !java.util.Objects.equals(pack.productRef(), request.productRef()))
                throw new IllegalStateException("BOOTSTRAP_PACK_DRIFT");
        }
    }

    private BootstrapPackRun matching(BootstrapPackRun run,String fingerprint) {
        if (run.requestFingerprint() == null) throw new IllegalStateException("BOOTSTRAP_RUN_UNPINNED");
        if (!run.requestFingerprint().equals(fingerprint))
            throw new IllegalStateException("BOOTSTRAP_REQUEST_CONFLICT");
        return run;
    }

    private BootstrapPackRun checkpoint(BootstrapPackRun initial,List<BootstrapPackStepRun> steps,
                                        BootstrapPackRunStatus status) {
        return new BootstrapPackRun(initial.packRunRef(),initial.packRef(),initial.packVersion(),
            initial.productRef(),initial.profileRef(),initial.mode(),status,initial.scope(),steps,
            initial.startedAt(),status == BootstrapPackRunStatus.RUNNING ? null : Instant.now(),
            initial.requestFingerprint(),initial.packDefinitionJson(),initial.runtimeArtifactSha256(),initial.checkpointRevision()+1);
    }

    private BootstrapPackStepRun executeStep(
        BootstrapPackDefinition pack,
        ApplyBootstrapPackRequest request,
        Map<String, Object> scope,
        BootstrapPackStepDefinition step,
        Map<String, BootstrapPackStepRun> completedSteps
    ) {
        Instant stepStartedAt = Instant.now();
        for (String dependency : step.dependsOn()) {
            BootstrapPackStepRun dependencyRun = completedSteps.get(dependency);
            if (
                dependencyRun == null ||
                dependencyRun.status() != BootstrapStepStatus.COMPLETED
            ) {
                return new BootstrapPackStepRun(
                    step.stepRef(),
                    BootstrapStepStatus.FAILED,
                    BootstrapStepOutcome.FAILED,
                    Map.of(
                        "reason",
                        "DEPENDENCY_NOT_SATISFIED",
                        "dependency",
                        dependency
                    ),
                    stepStartedAt,
                    Instant.now()
                );
            }
        }

        BootstrapPackStepHandler handler = resolveHandler(step);
        if (handler == null) {
            return new BootstrapPackStepRun(
                step.stepRef(),
                BootstrapStepStatus.FAILED,
                BootstrapStepOutcome.FAILED,
                Map.of("reason", "NO_HANDLER", "kind", step.kind().name()),
                stepStartedAt,
                Instant.now()
            );
        }

        BootstrapStepResult result;
        try (
            SecurityCallScope.Scope ignored =
                SecurityCallScope.openIgnoringRules()
        ) {
            result = handler.execute(
                new BootstrapStepRequest(
                    pack.packRef(),
                    pack.packVersion(),
                    pack.productRef(),
                    pack.profileRef(),
                    step.stepRef(),
                    step.kind(),
                    request.mode(),
                    request.actorRef(),
                    scope,
                    step.config()
                )
            );
            if (result == null)
                throw new IllegalStateException("BOOTSTRAP_STEP_RESULT_MISSING");
        } catch (RuntimeException failure) {
            return new BootstrapPackStepRun(
                step.stepRef(),
                BootstrapStepStatus.FAILED,
                BootstrapStepOutcome.FAILED,
                Map.of(
                    "reason", "HANDLER_EXECUTION_FAILED",
                    "errorType", failure.getClass().getName(),
                    "message", failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage()
                ),
                stepStartedAt,
                Instant.now()
            );
        }

        return new BootstrapPackStepRun(
            step.stepRef(),
            result.status(),
            result.outcome(),
            result.details(),
            stepStartedAt,
            Instant.now()
        );
    }

    private BootstrapPackStepHandler resolveHandler(
        BootstrapPackStepDefinition step
    ) {
        for (BootstrapPackStepHandler handler : handlers) {
            if (handler != null && handler.supports(step.kind())) {
                return handler;
            }
        }
        return null;
    }

    private Map<String, Object> buildScope(
        ApplyBootstrapPackRequest request,
        BootstrapPackDefinition pack
    ) {
        Map<String, Object> scope = new LinkedHashMap<>();
        putIfPresent(scope, "actorRef", request.actorRef());
        putIfPresent(scope, "packRef", pack.packRef());
        putIfPresent(scope, "packVersion", pack.packVersion());
        putIfPresent(
            scope,
            "productRef",
            firstNonBlank(request.productRef(), pack.productRef())
        );
        putIfPresent(scope, "profileRef", pack.profileRef());
        putIfPresent(scope, "environmentRef", request.environmentRef());
        putIfPresent(scope, "realmRef", request.realmRef());
        putIfPresent(scope, "tenantRef", request.tenantRef());
        putIfPresent(scope, "workspaceRef", request.workspaceRef());
        for (Map.Entry<String, Object> attribute : request.scopeAttributes().entrySet()) {
            String key = attribute.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Bootstrap scope attribute keys must be non-blank");
            }
            if (scope.containsKey(key)) {
                throw new IllegalArgumentException(
                    "Bootstrap scope attribute cannot replace reserved key: " + key
                );
            }
            if (attribute.getValue() != null) {
                scope.put(key, attribute.getValue());
            }
        }
        return scope;
    }

    private static Iterable<BootstrapPackStepHandler> toOrderedHandlers(
        Instance<BootstrapPackStepHandler> handlers
    ) {
        List<BootstrapPackStepHandler> ordered = new ArrayList<>();
        handlers.forEach(ordered::add);
        ordered.sort(
            Comparator.comparingInt(BootstrapPackStepHandler::priority)
        );
        return ordered;
    }

    private static void putIfPresent(
        Map<String, Object> target,
        String key,
        String value
    ) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }

    private static String buildRunRef() {
        return "bpr-" + UUID.randomUUID().toString().replace("-", "");
    }
}
