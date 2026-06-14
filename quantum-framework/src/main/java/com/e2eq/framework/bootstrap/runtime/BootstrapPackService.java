package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackApplyMode;
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

    public BootstrapPackRun apply(ApplyBootstrapPackRequest request) {
        BootstrapPackDefinition pack = registry.find(request.packRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown bootstrap pack: " + request.packRef()));

        Instant packStartedAt = Instant.now();
        Map<String, Object> scope = buildScope(request, pack);
        List<BootstrapPackStepRun> stepRuns = new ArrayList<>();
        Map<String, BootstrapPackStepRun> completedSteps = new LinkedHashMap<>();

        for (BootstrapPackStepDefinition step : pack.steps()) {
            BootstrapPackStepRun stepRun = executeStep(pack, request, scope, step, completedSteps);
            stepRuns.add(stepRun);
            completedSteps.put(step.stepRef(), stepRun);
            if (stepRun.status() == BootstrapStepStatus.FAILED) {
                BootstrapPackRun failedRun = new BootstrapPackRun(
                        buildRunRef(),
                        pack.packRef(),
                        pack.packVersion(),
                        pack.productRef(),
                        pack.profileRef(),
                        request.mode(),
                        BootstrapPackRunStatus.FAILED,
                        scope,
                        stepRuns,
                        packStartedAt,
                        Instant.now()
                );
                return runRepository.save(failedRun);
            }
        }

        BootstrapPackRun completedRun = new BootstrapPackRun(
                buildRunRef(),
                pack.packRef(),
                pack.packVersion(),
                pack.productRef(),
                pack.profileRef(),
                request.mode(),
                BootstrapPackRunStatus.COMPLETED,
                scope,
                stepRuns,
                packStartedAt,
                Instant.now()
        );
        return runRepository.save(completedRun);
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
            if (dependencyRun == null || dependencyRun.status() != BootstrapStepStatus.COMPLETED) {
                return new BootstrapPackStepRun(
                        step.stepRef(),
                        BootstrapStepStatus.FAILED,
                        BootstrapStepOutcome.FAILED,
                        Map.of("reason", "DEPENDENCY_NOT_SATISFIED", "dependency", dependency),
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
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            result = handler.execute(new BootstrapStepRequest(
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
            ));
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

    private BootstrapPackStepHandler resolveHandler(BootstrapPackStepDefinition step) {
        for (BootstrapPackStepHandler handler : handlers) {
            if (handler != null && handler.supports(step.kind())) {
                return handler;
            }
        }
        return null;
    }

    private Map<String, Object> buildScope(ApplyBootstrapPackRequest request, BootstrapPackDefinition pack) {
        Map<String, Object> scope = new LinkedHashMap<>();
        putIfPresent(scope, "packRef", pack.packRef());
        putIfPresent(scope, "packVersion", pack.packVersion());
        putIfPresent(scope, "productRef", firstNonBlank(request.productRef(), pack.productRef()));
        putIfPresent(scope, "profileRef", pack.profileRef());
        putIfPresent(scope, "environmentRef", request.environmentRef());
        putIfPresent(scope, "realmRef", request.realmRef());
        putIfPresent(scope, "tenantRef", request.tenantRef());
        putIfPresent(scope, "workspaceRef", request.workspaceRef());
        return scope;
    }

    private static Iterable<BootstrapPackStepHandler> toOrderedHandlers(Instance<BootstrapPackStepHandler> handlers) {
        List<BootstrapPackStepHandler> ordered = new ArrayList<>();
        handlers.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(BootstrapPackStepHandler::priority));
        return ordered;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
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
