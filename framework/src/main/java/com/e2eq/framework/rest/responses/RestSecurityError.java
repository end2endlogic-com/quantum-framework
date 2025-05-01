package com.e2eq.framework.rest.responses;

import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.rest.models.RestError;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
public class RestSecurityError extends RestError {
    protected SecurityCheckResponse securityResponse;
}
