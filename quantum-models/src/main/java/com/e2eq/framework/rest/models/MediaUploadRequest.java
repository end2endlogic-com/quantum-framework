package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Client-controlled metadata used to prepare a direct media upload. */
@RegisterForReflection
public class MediaUploadRequest {

    @NotBlank
    @Size(max = 512)
    public String displayFileName;

    @NotBlank
    @Size(max = 255)
    public String contentType;

    @Positive
    public long contentLength;

    @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "sha256 must be a 64 character hexadecimal digest")
    public String sha256;

    @Size(max = 255)
    public String purpose;
    public MediaReference.Classification classification = MediaReference.Classification.INTERNAL;
    @Size(max = 50)
    public Map<String, Object> metadata;
}
