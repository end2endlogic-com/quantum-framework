package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Application-scoped realm choice and the tenants selectable inside it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessibleRealmContext {
    private String applicationId;
    private AccessibleRealmInfo realm;
    private List<AccessibleTenantInfo> tenants = new ArrayList<>();
}
