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
        FIXED,
        /**
         * Derive the DataDomain from the values of the ingested source row plus the
         * source binding metadata (see {@link #componentBinding}). Used by the
         * source/ingestion write path where there is no authenticated principal to
         * supply a DataDomain. Only meaningful when {@link #componentBinding} is set.
         */
        FROM_SOURCE
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

    /**
     * Per-component binding used iff {@link #resolutionMode} == {@link ResolutionMode#FROM_SOURCE}.
     * Describes how each DataDomain component (orgRefName, accountNum, tenantId, dataSegment,
     * ownerId) is derived from either a literal value or an attribute of the ingested source row.
     *
     * <p>Nullable: existing stored policy JSON predates this field and will deserialize with
     * {@code componentBinding == null}; such entries are never {@code FROM_SOURCE} and retain
     * today's behavior.</p>
     */
    protected DataDomainComponentBinding componentBinding;
}
