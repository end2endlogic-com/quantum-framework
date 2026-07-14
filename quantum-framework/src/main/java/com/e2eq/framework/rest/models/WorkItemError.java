package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.tasks.WorkItemOperationException;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record WorkItemError(WorkItemOperationException.Code code, String message) {
}
