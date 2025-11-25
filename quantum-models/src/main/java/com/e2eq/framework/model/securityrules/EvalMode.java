package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum EvalMode {
    LEGACY,
    AUTO,
    STRICT
}
