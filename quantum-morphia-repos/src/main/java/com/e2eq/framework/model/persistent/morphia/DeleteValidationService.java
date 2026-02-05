package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that an entity may be deleted before a delete is performed.
 * Enforces the same checks as {@link MorphiaRepo} delete path so that callers
 * that bypass the repo (e.g. Query Gateway) still get referential integrity
 * and ontology BLOCK_IF_REFERENCED behavior.
 *
 * <ul>
 *   <li><b>Back-references ({@literal @}TrackReferences):</b> If other entities
 *       still reference this one, throws {@link ReferentialIntegrityViolationException}.</li>
 *   <li><b>Pre-delete hooks (e.g. ontology BLOCK_IF_REFERENCED):</b> Invokes
 *       all {@link PreDeleteHook} beans; any hook may throw to block deletion.</li>
 * </ul>
 */
@ApplicationScoped
public class DeleteValidationService {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    Instance<PreDeleteHook> preDeleteHooks;

    /**
     * Validates that the entity may be deleted in the given realm.
     * Throws if the entity has active back-references or if a pre-delete hook blocks.
     *
     * @param realmId realm (tenant) for the datastore
     * @param entity  entity that is about to be deleted (must be loaded with references populated)
     * @throws ReferentialIntegrityViolationException if other entities still reference this one
     * @throws RuntimeException                       if a pre-delete hook blocks (e.g. ontology BLOCK_IF_REFERENCED)
     */
    public void validateBeforeDelete(String realmId, UnversionedBaseModel entity) throws ReferentialIntegrityViolationException {
        if (entity == null) {
            return;
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realmId);
        ClassLoader cl = entity.getClass().getClassLoader();

        // 1) Back-reference check: @TrackReferences
        if (entity.getReferences() != null && !entity.getReferences().isEmpty()) {
            Set<ReferenceEntry> stillReferenced = new HashSet<>();
            for (ReferenceEntry ref : entity.getReferences()) {
                try {
                    Class<?> refType = cl.loadClass(ref.getType());
                    Query<?> q = ds.find(refType).filter(Filters.eq("_id", ref.getReferencedId()));
                    if (q.count() > 0) {
                        stillReferenced.add(ref);
                    }
                } catch (ClassNotFoundException e) {
                    Log.warnf("Failed to load class %s for reference check; treating as stale", ref.getType());
                }
            }
            if (!stillReferenced.isEmpty()) {
                String classes = stillReferenced.stream()
                        .map(ReferenceEntry::getType)
                        .distinct()
                        .collect(Collectors.joining(", "));
                throw new ReferentialIntegrityViolationException(
                        "Can not delete object because it has references from other objects to this one that would corrupt the relationship. Referencing classes: " + classes);
            }
        }

        // 2) Pre-delete hooks (e.g. OntologyDeleteHook with BLOCK_IF_REFERENCED)
        if (preDeleteHooks != null) {
            for (PreDeleteHook hook : preDeleteHooks) {
                try {
                    hook.beforeDelete(realmId, entity);
                } catch (Throwable t) {
                    throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
                }
            }
        }
    }
}
