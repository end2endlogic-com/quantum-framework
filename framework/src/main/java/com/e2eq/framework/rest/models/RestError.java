package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.general.ValidationViolation;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.ConstraintViolation;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.Set;

@Data
@EqualsAndHashCode
@SuperBuilder
@RegisterForReflection
public class RestError {
   protected int status;
   protected int reasonCode;
   protected String statusMessage;
   protected String reasonMessage;
   protected String debugMessage;
   protected SecurityCheckResponse securityResponse;
   protected Set<String> constraintViolations;
}
