package com.e2eq.framework.rest.models;


import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Set;

@Data
@EqualsAndHashCode
@SuperBuilder
@RegisterForReflection
@NoArgsConstructor
@ToString
public class RestError {
   protected int status;
   protected int reasonCode;
   protected String statusMessage;
   protected String reasonMessage;
   protected String debugMessage;
   protected Set<String> constraintViolations;
}
