package com.e2eq.framework.rest.resources;

import com.e2eq.framework.bootstrap.model.BootstrapPackApplyMode;
import com.e2eq.framework.bootstrap.model.BootstrapStepKind;
import com.e2eq.framework.bootstrap.model.BootstrapStepOutcome;
import com.e2eq.framework.bootstrap.model.BootstrapStepRequest;
import com.e2eq.framework.bootstrap.model.BootstrapStepResult;
import com.e2eq.framework.bootstrap.model.BootstrapStepStatus;
import com.e2eq.framework.bootstrap.spi.BootstrapPackStepHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class BootstrapPackAdminTestStepHandler implements BootstrapPackStepHandler {

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public boolean supports(BootstrapStepKind kind) {
        return kind == BootstrapStepKind.APP_SERVICE || kind == BootstrapStepKind.ASSERTION;
    }

    @Override
    public BootstrapStepResult execute(BootstrapStepRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("packRef", request.packRef());
        details.put("stepRef", request.stepRef());
        details.put("realmRef", request.scope().get("realmRef"));
        details.put("actorRef", request.actorRef());
        details.put("mode", request.mode().name());
        details.put("packKey", request.config().get("packKey"));

        BootstrapStepOutcome outcome = request.mode() == BootstrapPackApplyMode.VALIDATE_ONLY
                ? BootstrapStepOutcome.ALREADY_PRESENT
                : BootstrapStepOutcome.CREATED;
        return new BootstrapStepResult(BootstrapStepStatus.COMPLETED, outcome, details);
    }
}
