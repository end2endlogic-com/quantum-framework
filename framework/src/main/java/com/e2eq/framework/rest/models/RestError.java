package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
@Builder
@RegisterForReflection
public class RestError {
   protected int status;
   protected int reasonCode;
   protected String statusMessage;
   protected String reasonMessage;
   protected String debugMessage;
   protected SecurityCheckResponse securityResponse;
}
