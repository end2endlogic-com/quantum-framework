package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import java.util.List;

@Entity("DataDomainPolicy")
@RegisterForReflection
public @Data class DataDomainPolicyEntry {

    public enum ResolutionMode {
        FROM_CREDENTIAL,
        FIXED
    }

    // Optional legacy fields – kept for compatibility with existing structures
    protected String functionalDomainString;
    protected String functionalActionString;

    // If mode == FIXED, the first entry (if present) will be used as the fixed value
    protected List<DataDomain> dataDomains;

    // Optional: future use for filtering/validation
    protected String filter;

    // New: how to resolve the dataDomain for a matching rule. Defaults to FROM_CREDENTIAL
    protected ResolutionMode resolutionMode = ResolutionMode.FROM_CREDENTIAL;
}
