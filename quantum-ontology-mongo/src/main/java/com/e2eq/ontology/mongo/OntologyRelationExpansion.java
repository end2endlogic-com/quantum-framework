package com.e2eq.ontology.mongo;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.compiler.mongo.MongoAggregationCompiler;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansion;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException.Code;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.security.runtime.RuleFilterComposition;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.core.StableRelationGuard;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.query.filters.Filter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

/**
 * {@code expand(@relation)} over the ontology edge store (P3a).
 *
 * <p>For a page of root rows already read under the root policy: one grouped edge read per
 * DataDomain for the page's ids ({@link ComputedEdgeReader#edgesFromSrcs}, so LAZY and ONDEMAND
 * providers are included), then one read per target type of the reached ids under that type's own
 * row and field policy, then a merge under {@code _rel.<relation>}. The target answers to the rules
 * of the request's functional area and of its own {@code @FunctionalMapping} area, as the query
 * gateway's field-path expand does, so it is never wider than either.</p>
 *
 * <p>Fails closed ({@link RelationExpansionException}) before any row changes: an unknown or
 * internal relation (only stable relations may be bound), an edge target type with no mapped entity
 * or no {@code @FunctionalMapping}, an unresolvable policy, or more distinct targets than
 * {@code quantum.ontology.expand.max-targets}. A target the policy hides is simply not returned.</p>
 */
@ApplicationScoped
@Unremovable // looked up through RelationExpansion.current(), never injected
public class OntologyRelationExpansion implements RelationExpansion {

    static final String CONSUMER = "query:expand";

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Inject
    ComputedEdgeReader edgeReader;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    RuleContext ruleContext;

    @ConfigProperty(name = "quantum.ontology.expand.max-targets", defaultValue = "1000")
    int maxTargets;

    @Override
    public void hydrate(String realmId, Class<? extends UnversionedBaseModel> rootType, List<Document> rows,
                        List<String> relations) {
        if (relations == null || relations.isEmpty() || rows == null || rows.isEmpty()) return;
        OntologyRegistry registry = registryProvider.getRegistryForRealm(realmId);
        Map<String, PropertyDef> defs = new LinkedHashMap<>();
        for (String relation : relations) {
            defs.put(relation, requireExpandable(registry, relation));
        }

        // Row ids grouped by the DataDomain their edges were written under
        Map<DataDomain, List<String>> idsByDomain = new LinkedHashMap<>();
        for (Document row : rows) {
            String id = idOf(row);
            idsByDomain.computeIfAbsent(dataDomainOf(row), __ -> new ArrayList<>()).add(id);
        }

        // relation -> src -> ordered targets (id, type)
        Map<String, Map<String, LinkedHashMap<String, String>>> reached = new LinkedHashMap<>();
        Map<String, Set<String>> idsByType = new LinkedHashMap<>();
        Map<String, String> relationByType = new HashMap<>();
        int distinct = 0;
        for (String relation : relations) {
            Map<String, LinkedHashMap<String, String>> bySrc = new HashMap<>();
            for (Map.Entry<DataDomain, List<String>> group : idsByDomain.entrySet()) {
                for (Reasoner.Edge e : edgeReader.edgesFromSrcs(realmId, group.getKey(), relation, group.getValue())) {
                    String type = e.dstType() != null ? e.dstType() : defs.get(relation).range().orElse(null);
                    relationByType.putIfAbsent(type, relation);
                    bySrc.computeIfAbsent(e.srcId(), __ -> new LinkedHashMap<>()).put(e.dstId(), type);
                    if (idsByType.computeIfAbsent(type, __ -> new LinkedHashSet<>()).add(e.dstId())) {
                        distinct++;
                    }
                }
            }
            if (distinct > maxTargets) {
                throw new RelationExpansionException(Code.TOO_MANY_TARGETS, relation, "expand(@" + relation
                        + ") reaches more than " + maxTargets + " targets for this page "
                        + "(quantum.ontology.expand.max-targets); narrow the query or lower the page size");
            }
            reached.put(relation, bySrc);
        }

        Map<String, Document> targets = readTargets(realmId, idsByType, relationByType);

        for (Document row : rows) {
            String id = idOf(row);
            Document rel = row.get(FIELD) instanceof Document existing ? existing : new Document();
            for (String relation : relations) {
                List<Document> hits = new ArrayList<>();
                LinkedHashMap<String, String> dsts = reached.get(relation).get(id);
                if (dsts != null) {
                    for (Map.Entry<String, String> dst : dsts.entrySet()) {
                        Document target = targets.get(targetKey(dst.getValue(), dst.getKey()));
                        if (target != null) hits.add(target);
                    }
                }
                rel.put(relation, defs.get(relation).functional() ? (hits.isEmpty() ? null : hits.get(0)) : hits);
            }
            row.put(FIELD, rel);
        }
    }

    /** The relation's definition, when it is declared and stable. */
    static PropertyDef requireExpandable(OntologyRegistry registry, String relation) {
        List<StableRelationGuard.BindingViolation> violations =
                StableRelationGuard.bindingViolations(registry, List.of(relation), CONSUMER);
        if (!violations.isEmpty()) {
            StableRelationGuard.BindingViolation v = violations.get(0);
            Code code = v.code() == StableRelationGuard.BindingCode.BINDING_UNKNOWN_RELATION
                    ? Code.UNKNOWN_RELATION : Code.INTERNAL_RELATION;
            throw new RelationExpansionException(code, relation, v.message());
        }
        return registry.propertyOf(relation).orElseThrow();
    }

    /** Reads every reached target under its type's policy, keyed by {@link #targetKey}. */
    private Map<String, Document> readTargets(String realmId, Map<String, Set<String>> idsByType,
                                              Map<String, String> relationByType) {
        Map<String, Document> out = new HashMap<>();
        if (idsByType.isEmpty()) return out;
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realmId);
        Map<String, EntityModel> entities = entitiesByClassId(ds);
        for (Map.Entry<String, Set<String>> group : idsByType.entrySet()) {
            String type = group.getKey();
            String relation = relationByType.get(type);
            EntityModel model = type != null ? entities.get(type) : null;
            if (model == null || !UnversionedBaseModel.class.isAssignableFrom(model.getType())) {
                throw new RelationExpansionException(Code.NO_TARGET_TYPE, relation,
                        "expand(@" + relation + ") reaches targets of type '" + type
                                + "', which is not a mapped entity in realm '" + realmId + "'");
            }
            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> targetType = (Class<? extends UnversionedBaseModel>) model.getType();
            Policy policy = policyFor(ds, targetType, relation);
            Document match = new Document("_id", new Document("$in", toIds(group.getValue())));
            if (policy.rowMatch() != null && !policy.rowMatch().isEmpty()) {
                match = new Document("$and", List.of(match, policy.rowMatch()));
            }
            var find = ds.getDatabase().getCollection(model.collectionName()).find(match);
            if (!policy.excludedFields().isEmpty()) {
                Document projection = new Document();
                policy.excludedFields().forEach(f -> projection.append(f, 0));
                find = find.projection(projection);
            }
            for (Document target : find) {
                out.put(targetKey(type, String.valueOf(target.get("_id"))), target);
            }
        }
        return out;
    }

    private record Policy(Document rowMatch, Set<String> excludedFields) {}

    /**
     * Row filter and excluded fields for {@code targetType}: the request area's rules and the
     * target's own {@code @FunctionalMapping} area's rules together.
     */
    private Policy policyFor(MorphiaDatastore ds, Class<? extends UnversionedBaseModel> targetType, String relation) {
        FunctionalMapping mapping = targetType.getAnnotation(FunctionalMapping.class);
        if (mapping == null) {
            throw new RelationExpansionException(Code.TARGET_NOT_GOVERNED, relation,
                    "expand target " + targetType.getSimpleName() + " must declare @FunctionalMapping for policy evaluation");
        }
        if (SecurityContext.isIgnoringRules()) {
            return new Policy(null, Set.of());
        }
        Optional<PrincipalContext> pc = SecurityContext.getPrincipalContext();
        Optional<ResourceContext> rc = SecurityContext.getResourceContext();
        if (pc.isEmpty() || rc.isEmpty()) {
            throw new RelationExpansionException(Code.TARGET_NOT_GOVERNED, relation,
                    "expand target " + targetType.getSimpleName()
                            + " cannot be governed: the security context is not established for this request");
        }
        ResourceContext targetContext = new ResourceContext.Builder()
                .withRealm(rc.get().getRealm())
                .withApplicationId(rc.get().getApplicationId())
                .withArea(mapping.area())
                .withFunctionalDomain(mapping.domain())
                .withAction(rc.get().getAction())
                .withResourceId(rc.get().getResourceId())
                .build();
        try {
            List<Filter> rowRules = new ArrayList<>(ruleContext.getFilters(new ArrayList<>(), pc.get(), rc.get(), targetType));
            rowRules.addAll(ruleContext.getFilters(new ArrayList<>(), pc.get(), targetContext, targetType));
            Set<String> excluded = new LinkedHashSet<>();
            Set<String> base = ruleContext.getExcludedFieldPaths(pc.get(), rc.get());
            if (base != null) excluded.addAll(base);
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(targetContext)) {
                Set<String> own = ruleContext.getExcludedFieldPaths(pc.get(), targetContext);
                if (own != null) excluded.addAll(own);
            }
            Filter[] conjoined = RuleFilterComposition.conjoin(rowRules);
            Document match = conjoined.length == 0 ? null
                    : new MongoAggregationCompiler(ds).compileMatch(conjoined[0]);
            excluded.removeIf(f -> f == null || f.isBlank());
            return new Policy(match, excluded);
        } catch (RelationExpansionException e) {
            throw e;
        } catch (RuntimeException unresolved) {
            throw new RelationExpansionException(Code.TARGET_NOT_GOVERNED, relation,
                    "expand target " + targetType.getSimpleName() + ": its row or field policy could not be resolved ("
                            + unresolved.getMessage() + ")");
        }
    }

    /** Mapped entities by ontology class id ({@code @OntologyClass(id)}, else the simple name). */
    private static Map<String, EntityModel> entitiesByClassId(MorphiaDatastore ds) {
        Map<String, EntityModel> out = new HashMap<>();
        for (EntityModel model : ds.getMapper().getMappedEntities()) {
            out.putIfAbsent(classIdOf(model.getType()), model);
        }
        return out;
    }

    static String classIdOf(Class<?> type) {
        OntologyClass annotation = type.getAnnotation(OntologyClass.class);
        if (annotation != null && annotation.id() != null && !annotation.id().isBlank()) return annotation.id();
        return type.getSimpleName();
    }

    private static String idOf(Document row) {
        Object id = row.get("_id");
        if (id == null) {
            throw new IllegalArgumentException("expand(@relation) needs each row's _id; do not exclude it in the projection");
        }
        return id instanceof ObjectId oid ? oid.toHexString() : String.valueOf(id);
    }

    /** The DataDomain of {@code row}, else the principal's: edges are stored under their source's. */
    private static DataDomain dataDomainOf(Document row) {
        if (row.get("dataDomain") instanceof Document dd && dd.getString("orgRefName") != null
                && dd.getString("accountNum") != null && dd.getString("tenantId") != null) {
            Object segment = dd.get("dataSegment");
            return new DataDomain(dd.getString("orgRefName"), dd.getString("accountNum"), dd.getString("tenantId"),
                    segment instanceof Number n ? n.intValue() : 0,
                    dd.getString("ownerId") != null ? dd.getString("ownerId") : "");
        }
        return SecurityContext.getPrincipalContext().map(PrincipalContext::getDataDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "expand(@relation) needs each row's dataDomain or a principal DataDomain"));
    }

    private static List<Object> toIds(Collection<String> ids) {
        List<Object> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            out.add(ObjectId.isValid(id) ? new ObjectId(id) : id);
        }
        return out;
    }

    private static String targetKey(String type, String id) {
        return type + "\u0000" + id;
    }

}
