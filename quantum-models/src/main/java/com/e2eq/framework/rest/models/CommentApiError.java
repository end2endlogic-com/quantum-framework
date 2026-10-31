package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Stable typed error envelope for comment-chain operations. */
@RegisterForReflection
public record CommentApiError(String code, String message, int status) {
}
