package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Replaces the tenant-selection grant on a user's assignment in one realm. */
@RegisterForReflection
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantGrantRequest {
    private List<String> authorizedTenantIds;
    private String authorizedTenantRegEx;
}
