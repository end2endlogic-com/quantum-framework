package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Pattern;

/** Optional presentation controls for a short-lived media download grant. */
@RegisterForReflection
public class MediaDownloadGrantRequest {

    @Pattern(regexp = "^(inline|attachment)$")
    public String contentDisposition = "attachment";
}
