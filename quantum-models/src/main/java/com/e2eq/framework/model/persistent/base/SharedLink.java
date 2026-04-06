package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
@RegisterForReflection
public class SharedLink {
    private String url;
    private String token;
    private Instant createdAt;
    private Instant expiresAt;
    private EntityReference createdBy;
    private List<String> audiences;
    private String permission;
    private String notes;
}
