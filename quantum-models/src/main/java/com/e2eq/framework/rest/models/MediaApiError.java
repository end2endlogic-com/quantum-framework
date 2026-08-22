package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Stable typed error envelope for media-reference operations. */
@RegisterForReflection
public record MediaApiError(String code, String message, int status) {
}
