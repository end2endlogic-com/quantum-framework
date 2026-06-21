package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.List;

/**
 * SPI for listing source entity IDs of a given type within a {@link DataDomain}.
 *
 * <p>Used by {@link BulkRecomputeService} to walk all sources for a provider
 * during a bulk recompute. Each application registers one bean per source
 * entity type; the bulk service picks the matching enumerator by
 * {@link #supports(Class)}.</p>
 */
public interface SourceEntityEnumerator {

    /** True if this enumerator can list IDs for the given entity type. */
    boolean supports(Class<?> entityType);

    /**
     * Page through source entity IDs.
     *
     * @param realmId      realm
     * @param dataDomain   scoping domain
     * @param entityType   the source type
     * @param afterId      pagination cursor (exclusive); null on first call
     * @param limit        max IDs to return
     * @return up to {@code limit} ids ordered ascending; empty when exhausted
     */
    List<String> listIds(String realmId, DataDomain dataDomain,
                         Class<?> entityType, String afterId, int limit);
}
