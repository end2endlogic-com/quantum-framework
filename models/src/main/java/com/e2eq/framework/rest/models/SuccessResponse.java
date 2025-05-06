package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode( callSuper = true)
@RegisterForReflection
public class SuccessResponse extends ResponseBase {
}
