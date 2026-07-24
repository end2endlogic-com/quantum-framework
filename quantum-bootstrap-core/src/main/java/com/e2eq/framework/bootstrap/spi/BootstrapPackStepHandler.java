package com.e2eq.framework.bootstrap.spi;

import com.e2eq.framework.bootstrap.model.BootstrapStepKind;
import com.e2eq.framework.bootstrap.model.BootstrapStepRequest;
import com.e2eq.framework.bootstrap.model.BootstrapStepResult;

public interface BootstrapPackStepHandler {

    default int priority() {
        return 100;
    }

    boolean supports(BootstrapStepKind kind);

    BootstrapStepResult execute(BootstrapStepRequest request);
}
