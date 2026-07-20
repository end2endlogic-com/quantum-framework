package com.e2eq.framework.rest.models;


import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Set;

/**
 * Shared REST error payload.
 *
 * <p>The optional properties in this model are non-null when present in the
 * generated OpenAPI contract. Omitting absent values keeps the serialized
 * payload aligned with that contract; emitting explicit JSON {@code null}
 * values causes generated clients to reject the response before they can
 * surface the underlying API error.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
