package com.e2eq.framework.bootstrap;

import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackApplyMode;
import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.bootstrap.model.BootstrapPackStepDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapStepApplyPolicy;
import com.e2eq.framework.bootstrap.model.BootstrapStepKind;
import com.e2eq.framework.bootstrap.model.BootstrapStepOutcome;
import com.e2eq.framework.bootstrap.model.BootstrapStepRequest;
import com.e2eq.framework.bootstrap.model.BootstrapStepResult;
import com.e2eq.framework.bootstrap.model.BootstrapStepStatus;
import com.e2eq.framework.bootstrap.runtime.BootstrapPackService;
import com.e2eq.framework.bootstrap.runtime.CdiBootstrapPackRegistry;
import com.e2eq.framework.bootstrap.runtime.InMemoryBootstrapPackRunRepository;
import com.e2eq.framework.bootstrap.spi.BootstrapPackContributor;
import com.e2eq.framework.bootstrap.spi.BootstrapPackStepHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPackServiceTest {

    @Test
    void appliesPackAndRecordsRunHistory() {
        RecordingHandler handler = new RecordingHandler();
        BootstrapPackService service = new BootstrapPackService(
                new CdiBootstrapPackRegistry(List.of(new DemoPackContributor())),
                List.of(handler),
                new InMemoryBootstrapPackRunRepository()
        );

        BootstrapPackRun run = service.apply(new ApplyBootstrapPackRequest(
                "demo-pack",
                BootstrapPackApplyMode.APPLY_MISSING,
                null,
                "local",
                "b2bi-com",
                "b2bi.com",
                "studio",
                "tester"
        ));

        assertEquals(BootstrapPackRunStatus.COMPLETED, run.status());
        assertEquals(2, run.steps().size());
        assertEquals(List.of("ensure-a", "ensure-b"), handler.executedSteps);
        assertEquals("local", run.scope().get("environmentRef"));
        assertEquals("b2bi-com", run.scope().get("realmRef"));
        assertEquals(1, service.listRuns().size());
    }

    @Test
    void passesValidateOnlyModeIntoStepHandlers() {
        RecordingHandler handler = new RecordingHandler();
        BootstrapPackService service = new BootstrapPackService(
                new CdiBootstrapPackRegistry(List.of(new DemoPackContributor())),
                List.of(handler),
                new InMemoryBootstrapPackRunRepository()
        );

        BootstrapPackRun run = service.apply(new ApplyBootstrapPackRequest(
                "demo-pack",
                BootstrapPackApplyMode.VALIDATE_ONLY,
                null,
                null,
                null,
                null,
                null,
                "validator"
        ));

        assertEquals(BootstrapPackRunStatus.COMPLETED, run.status());
        assertTrue(handler.requestModes.stream().allMatch(mode -> mode == BootstrapPackApplyMode.VALIDATE_ONLY));
        assertTrue(run.steps().stream().allMatch(step -> step.outcome() == BootstrapStepOutcome.ALREADY_PRESENT));
    }

    @Test
    void failsWhenNoHandlerSupportsStepKind() {
        BootstrapPackService service = new BootstrapPackService(
                new CdiBootstrapPackRegistry(List.of(new DemoPackContributor())),
                List.of(),
                new InMemoryBootstrapPackRunRepository()
        );

        BootstrapPackRun run = service.apply(new ApplyBootstrapPackRequest(
                "demo-pack",
                BootstrapPackApplyMode.APPLY_MISSING,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals(BootstrapPackRunStatus.FAILED, run.status());
        assertEquals(BootstrapStepStatus.FAILED, run.steps().get(0).status());
        assertEquals("NO_HANDLER", run.steps().get(0).details().get("reason"));
    }

    private static final class DemoPackContributor implements BootstrapPackContributor {
        @Override
        public Collection<BootstrapPackDefinition> bootstrapPacks() {
            return List.of(new BootstrapPackDefinition(
                    "demo-pack",
                    "2026.04.0",
                    "b2bi",
                    "local-demo",
                    List.of(
                            new BootstrapPackStepDefinition(
                                    "ensure-a",
                                    "Ensure resource A exists",
                                    BootstrapStepKind.APP_SERVICE,
                                    BootstrapStepApplyPolicy.WHEN_MISSING,
                                    List.of(),
                                    Map.of("resource", "A")
                            ),
                            new BootstrapPackStepDefinition(
                                    "ensure-b",
                                    "Ensure resource B exists",
                                    BootstrapStepKind.APP_SERVICE,
                                    BootstrapStepApplyPolicy.WHEN_MISSING,
                                    List.of("ensure-a"),
                                    Map.of("resource", "B")
                            )
                    )
            ));
        }
    }

    private static final class RecordingHandler implements BootstrapPackStepHandler {
        private final List<String> executedSteps = new ArrayList<>();
        private final List<BootstrapPackApplyMode> requestModes = new ArrayList<>();

        @Override
        public boolean supports(BootstrapStepKind kind) {
            return kind == BootstrapStepKind.APP_SERVICE;
        }

        @Override
        public BootstrapStepResult execute(BootstrapStepRequest request) {
            executedSteps.add(request.stepRef());
            requestModes.add(request.mode());
            BootstrapStepOutcome outcome = request.mode() == BootstrapPackApplyMode.VALIDATE_ONLY
                    ? BootstrapStepOutcome.ALREADY_PRESENT
                    : BootstrapStepOutcome.CREATED;
            return new BootstrapStepResult(
                    BootstrapStepStatus.COMPLETED,
                    outcome,
                    Map.of("handledBy", "RecordingHandler", "resource", request.config().get("resource"))
            );
        }
    }
}
