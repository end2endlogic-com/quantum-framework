package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.PostPersistHook;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-persist hook that auto-materializes ontology edges for annotated entities.
 * All edges are scoped by the entity's DataDomain to ensure isolation.
 */
@ApplicationScoped
public class OntologyWriteHook implements PostPersistHook {

    @Inject AnnotatedEdgeExtractor extractor;
    @Inject OntologyMaterializer materializer;
    @Inject CascadeExecutor cascadeExecutor;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject Instance<OntologyEdgeProvider> providers;

    @Override
    public void afterPersist(String realmId, Object entity) {
        Class<?> entityClass = entity.getClass();

        // Check if entity participates in ontology via annotations
        var metaOpt = extractor.metaOf(entityClass);
        boolean hasOntologyAnnotation = metaOpt.isPresent();

        // Check if any provider supports this entity type
        boolean hasProviderSupport = false;
        for (OntologyEdgeProvider p : providers) {
            if (p.supports(entityClass)) {
                hasProviderSupport = true;
                break;
            }
        }

        // If neither annotations nor providers apply, skip this entity
        if (!hasOntologyAnnotation && !hasProviderSupport) {
            return;
        }

        try {
            var oc = entityClass.getAnnotation(OntologyClass.class);
            long propCount = java.util.Arrays.stream(entityClass.getDeclaredFields())
                .filter(f -> f.getAnnotation(com.e2eq.ontology.annotations.OntologyProperty.class) != null)
                .count();
            io.quarkus.logging.Log.infof("[DEBUG_LOG] afterPersist on %s, has @OntologyClass=%s, annotatedProps=%d, hasProviderSupport=%s",
                entityClass.getName(), (oc != null), propCount, hasProviderSupport);
        } catch (Exception ignored) {}

        // Determine entity type: prefer annotation ID, fallback to simple class name
        String entityType;
        if (hasOntologyAnnotation) {
            entityType = metaOpt.get().classId;
        } else {
            // For provider-only entities, derive type from @OntologyClass annotation if present
            OntologyClass ontologyClassAnnotation = entityClass.getAnnotation(OntologyClass.class);
            if (ontologyClassAnnotation != null && !ontologyClassAnnotation.id().isEmpty()) {
                entityType = ontologyClassAnnotation.id();
            } else {
                entityType = entityClass.getSimpleName();
            }
        }

        // Extract DataDomain from entity - required for proper scoping
        DataDomain dataDomain = extractDataDomain(entity, realmId);
        if (dataDomain == null) {
            io.quarkus.logging.Log.warnf("Cannot materialize ontology edges: entity %s has no DataDomain", entityClass.getName());
            return;
        }

        // Collect edges from annotations (if any)
        List<Reasoner.Edge> explicit = new ArrayList<>();
        if (hasOntologyAnnotation) {
            explicit.addAll(extractor.fromEntity(realmId, entity));
        }

        // Extend with SPI-provided edges
        DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
        try {
            for (OntologyEdgeProvider p : providers) {
                if (p.supports(entityClass)) {
                    var extra = p.edges(realmId, dataDomainInfo, entity);
                    if (extra != null && !extra.isEmpty()) {
                        io.quarkus.logging.Log.infof("[DEBUG_LOG] Provider %s contributed %d edges for %s",
                            p.getClass().getSimpleName(), extra.size(), entityClass.getSimpleName());
                        explicit.addAll(extra);
                    }
                }
            }
        } catch (Throwable t) {
            io.quarkus.logging.Log.warn("[DEBUG_LOG] OntologyWriteHook: provider extension failed", t);
        }

        try {
            io.quarkus.logging.Log.infof("[DEBUG_LOG] OntologyWriteHook.afterPersist entityType=%s, realm=%s, dataDomain=%s/%s/%s, explicitEdges=%d",
                entityType, realmId, dataDomain.getOrgRefName(), dataDomain.getAccountNum(), dataDomain.getTenantId(), explicit.size());
            for (Reasoner.Edge e : explicit) {
                io.quarkus.logging.Log.infof("[DEBUG_LOG]   explicit: (%s)-['%s']->(%s)", e.srcId(), e.p(), e.dstId());
            }
        } catch (Exception ignored) {}

        String srcId = extractor.idOf(entity);
        // Capture prior edges for this source to support cascade diffs - now scoped by DataDomain
        java.util.List<OntologyEdge> priorAll = edgeRepo.findBySrc(dataDomain, srcId);
        java.util.List<OntologyEdge> priorExplicit = new java.util.ArrayList<>();
        for (OntologyEdge e : priorAll) if (!e.isInferred()) priorExplicit.add(e);
        // First, apply materialization so explicit edges are upserted and stale ones pruned
        materializer.apply(dataDomain, srcId, entityType, explicit);
        // Then handle ORPHAN_REMOVE cascade based on prior vs new state and repo contents
        try { cascadeExecutor.onAfterPersist(realmId, dataDomain, srcId, entity, priorExplicit, explicit); } catch (Throwable ignored) {}
    }
    
    /**
     * Extracts DataDomain from an entity. If the entity doesn't have a DataDomain,
     * creates a fallback one using the realmId as tenantId with default values.
     */
    private DataDomain extractDataDomain(Object entity, String realmId) {
        if (entity instanceof UnversionedBaseModel model) {
            DataDomain dd = model.getDataDomain();
            if (dd != null && dd.getOrgRefName() != null && dd.getAccountNum() != null && dd.getTenantId() != null) {
                return dd;
            }
        }
        // Fallback: create minimal DataDomain from realmId (for backward compatibility with tests)
        // In production, entities should always have a proper DataDomain set
        DataDomain fallback = new DataDomain();
        fallback.setOrgRefName("ontology");
        fallback.setAccountNum("0000000000");
        fallback.setTenantId(realmId);
        fallback.setOwnerId("system");
        fallback.setDataSegment(0);
        io.quarkus.logging.Log.warnf("Entity %s missing DataDomain, using fallback with tenantId=%s", entity.getClass().getSimpleName(), realmId);
        return fallback;
    }
}
