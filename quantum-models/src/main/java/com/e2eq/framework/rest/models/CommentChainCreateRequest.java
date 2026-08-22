package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.collaboration.CommentSubjectReference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Client-controlled fields for creating a comment chain. */
@RegisterForReflection
public class CommentChainCreateRequest {
    @Valid
    @NotNull
    public CommentSubjectReference subject;

    public String displayName;
    public Map<String, Object> context;
}
