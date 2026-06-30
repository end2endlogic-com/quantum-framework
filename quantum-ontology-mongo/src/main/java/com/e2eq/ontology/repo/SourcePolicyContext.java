package com.e2eq.ontology.repo;

import com.e2eq.framework.model.security.DataDomainPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The governance context for a single source's ingest batch (S3).
 *
 * <p>Carries the {@code sourceId} the batch was ingested from, the source's
 * {@link DataDomainPolicy} (taken EXPLICITLY — the source-bound synthetic principal /
 * SecurityCallScope is deferred to S4), and any literal base bindings to fold into each row's
 * {@code SourceAttributes} (e.g. a fixed orgRefName/accountNum supplied by the source registration
 * rather than present in every row).</p>
 *
 * <p>Plain value holder with no Mongo/Quarkus coupling so the ingest resolution can be exercised in
 * unit tests.</p>
 */
public final class SourcePolicyContext {

    private final String sourceId;
    private final DataDomainPolicy policy;
    private final Map<String, Object> baseBindings;

    public SourcePolicyContext(String sourceId, DataDomainPolicy policy, Map<String, Object> baseBindings) {
        this.sourceId = sourceId;
        this.policy = policy;
        this.baseBindings = (baseBindings == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(baseBindings));
    }

    public SourcePolicyContext(String sourceId, DataDomainPolicy policy) {
        this(sourceId, policy, null);
    }

    /** The id of the source this batch was ingested from. */
    public String getSourceId() {
        return sourceId;
    }

    /** The source's DataDomainPolicy used to govern placement. May be null → all rows quarantine. */
    public DataDomainPolicy getPolicy() {
        return policy;
    }

    /** Literal base bindings folded into every row's SourceAttributes. Never null. */
    public Map<String, Object> getBaseBindings() {
        return baseBindings;
    }
}
