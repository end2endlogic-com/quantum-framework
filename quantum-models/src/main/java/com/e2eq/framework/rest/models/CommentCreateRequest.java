package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.collaboration.Comment;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Client-controlled fields for adding a comment. Actor and timestamps are server-owned. */
@RegisterForReflection
public class CommentCreateRequest {
    @NotBlank
    @Size(max = 100_000)
    public String body;

    public Comment.BodyFormat bodyFormat = Comment.BodyFormat.MARKDOWN;
    public String parentCommentId;

    @Valid
    public List<EntityReference> mediaReferences = new ArrayList<>();

    public String requestId;
    public Map<String, Object> metadata;
}
