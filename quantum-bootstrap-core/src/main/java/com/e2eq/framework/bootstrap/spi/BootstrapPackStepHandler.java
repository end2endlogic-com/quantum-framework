package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapStepKind;
import com.e2eq.framework.bootstrap.model.BootstrapStepRequest;
import com.e2eq.framework.bootstrap.model.BootstrapStepResult;

public interface BootstrapPackStepHandler {

    default int priority() {
        return 100;
    }

    boolean supports(BootstrapStepKind kind);

    /** Read-only lowering into a frozen execution config, called for every step before effects. */
    default java.util.Map<String, Object> prepare(BootstrapStepRequest request) {
        return request.config();
    }

    /** Recovery may acknowledge committed effects, but must never repeat an uncertain write. */
    default BootstrapStepResult reconcile(BootstrapStepRequest request) {
        throw new IllegalStateException("BOOTSTRAP_RECOVERY_UNSUPPORTED");
    }

    BootstrapStepResult execute(BootstrapStepRequest request);
}
