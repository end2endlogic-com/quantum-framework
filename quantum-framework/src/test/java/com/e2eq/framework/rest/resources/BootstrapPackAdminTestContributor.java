package com.e2eq.framework.rest.resources;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackStepDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapStepApplyPolicy;
import com.e2eq.framework.bootstrap.model.BootstrapStepKind;
import com.e2eq.framework.bootstrap.spi.BootstrapPackContributor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BootstrapPackAdminTestContributor implements BootstrapPackContributor {

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Collection<BootstrapPackDefinition> bootstrapPacks() {
        return List.of(
                new BootstrapPackDefinition(
                        "demo-bootstrap-a",
                        "1.0.0",
                        "framework-test",
                        "test",
                        List.of(new BootstrapPackStepDefinition(
                                "ensure-a",
                                "Create demo bootstrap pack A markers.",
                                BootstrapStepKind.APP_SERVICE,
                                BootstrapStepApplyPolicy.WHEN_MISSING,
                                List.of(),
                                Map.of("packKey", "A")
                        ))
                ),
                new BootstrapPackDefinition(
                        "demo-bootstrap-b",
                        "1.0.0",
                        "framework-test",
                        "test",
                        List.of(
                                new BootstrapPackStepDefinition(
                                        "ensure-b",
                                        "Create demo bootstrap pack B markers.",
                                        BootstrapStepKind.APP_SERVICE,
                                        BootstrapStepApplyPolicy.WHEN_MISSING,
                                        List.of(),
                                        Map.of("packKey", "B")
                                ),
                                new BootstrapPackStepDefinition(
                                        "assert-b",
                                        "Validate demo bootstrap pack B markers.",
                                        BootstrapStepKind.ASSERTION,
                                        BootstrapStepApplyPolicy.ALWAYS_IDEMPOTENT,
                                        List.of("ensure-b"),
                                        Map.of("packKey", "B")
                                )
                        )
                )
        );
    }
}
