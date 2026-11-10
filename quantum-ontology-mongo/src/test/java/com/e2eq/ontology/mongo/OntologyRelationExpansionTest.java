package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansion;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.ontology.mongo.it.ItExpandCustomer;
import com.e2eq.ontology.mongo.it.ItExpandNote;
import com.e2eq.ontology.mongo.it.ItExpandShipment;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code expand(@relation)}: targets of a stable relation, read under the target's own policy (P3a). */
@QuarkusTest
class OntologyRelationExpansionTest {

    private static final String REALM = "test-ontology-expand";

    @Inject
    RelationExpansion expansion;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Inject
    ComputedEdgeReader edgeReader;

    @Inject
    RuleContext ruleContext;

    @io.quarkus.test.InjectMock
    com.e2eq.framework.model.auth.AuthProviderFactory authProviderFactory;

    private MorphiaDatastore ds;
    private DataDomain dd;
    private ItExpandCustomer withShipments;
    private ItExpandCustomer withNone;
    private ItExpandShipment open;
    private ItExpandShipment closed;

    @BeforeEach
    void setup() {
        org.mockito.Mockito.when(authProviderFactory.getAuthProvider()).thenReturn(
                org.mockito.Mockito.mock(com.e2eq.framework.model.auth.AuthProvider.class));
        ds = dataStoreWrapper.getDataStore(REALM);
        dd = new DataDomain("expand-org", "1111111111", REALM, 0, "system");
        privileged(() -> edgeRepo.deleteAll(REALM));
        for (String c : List.of("it_expand_customers", "it_expand_shipments", "it_expand_notes")) {
            ds.getDatabase().getCollection(c).deleteMany(new Document());
        }
        registryProvider.invalidateRealm(REALM);
        ruleContext.clear();

        withShipments = save(new ItExpandCustomer(), "C-1", "open");
        withNone = save(new ItExpandCustomer(), "C-2", "open");
        open = save(new ItExpandShipment(), "S-OPEN", "open");
        closed = save(new ItExpandShipment(), "S-CLOSED", "closed");
        edge(withShipments, "expandShipments", open);
        edge(withShipments, "expandShipments", closed);
        edge(withShipments, "expandPrimaryShipment", open);
        edge(withShipments, "expandInternalShipments", open);
    }

    @AfterEach
    void cleanup() {
        ruleContext.clear();
    }

    @Test
    void aStableRelationHydratesItsTargetsUnderRel() {
        List<Document> rows = customers();
        privileged(() -> expansion.hydrate(REALM, ItExpandCustomer.class, rows,
                List.of("expandShipments", "expandPrimaryShipment")));

        Document first = row(rows, withShipments);
        assertEquals(List.of("S-CLOSED", "S-OPEN"), refNames(first.get("_rel", Document.class).getList("expandShipments", Document.class)));
        assertEquals("S-OPEN", first.get("_rel", Document.class).get("expandPrimaryShipment", Document.class).getString("refName"),
                "a functional relation hydrates one target");
        Document second = row(rows, withNone);
        assertEquals(List.of(), second.get("_rel", Document.class).getList("expandShipments", Document.class));
        assertNull(second.get("_rel", Document.class).get("expandPrimaryShipment"));
    }

    @Test
    void anInternalOrUnknownRelationIsRefusedAndNoRowChanges() {
        List<Document> rows = customers();
        RelationExpansionException internal = assertThrows(RelationExpansionException.class, () -> privileged(() ->
                expansion.hydrate(REALM, ItExpandCustomer.class, rows, List.of("expandInternalShipments"))));
        assertEquals(RelationExpansionException.Code.INTERNAL_RELATION, internal.code());
        assertTrue(internal.getMessage().contains("only stable relations may be bound"), internal.getMessage());

        RelationExpansionException unknown = assertThrows(RelationExpansionException.class, () -> privileged(() ->
                expansion.hydrate(REALM, ItExpandCustomer.class, rows, List.of("expandShipments", "nope"))));
        assertEquals(RelationExpansionException.Code.UNKNOWN_RELATION, unknown.code());
        assertEquals("nope", unknown.relation());
        assertTrue(rows.stream().noneMatch(r -> r.containsKey(RelationExpansion.FIELD)));
    }

    @Test
    void aTargetWithoutAFunctionalMappingIsRefused() {
        ItExpandNote note = save(new ItExpandNote(), "N-1", "open");
        edge(withShipments, "expandNotes", note);
        List<Document> rows = customers();
        RelationExpansionException e = assertThrows(RelationExpansionException.class, () -> privileged(() ->
                expansion.hydrate(REALM, ItExpandCustomer.class, rows, List.of("expandNotes"))));
        assertEquals(RelationExpansionException.Code.TARGET_NOT_GOVERNED, e.code());
        assertTrue(rows.stream().noneMatch(r -> r.containsKey(RelationExpansion.FIELD)));
    }

    @Test
    void morePageTargetsThanTheCapAreRefused() {
        OntologyRelationExpansion capped = new OntologyRelationExpansion();
        capped.registryProvider = registryProvider;
        capped.edgeReader = edgeReader;
        capped.morphiaDataStoreWrapper = dataStoreWrapper;
        capped.ruleContext = ruleContext;
        capped.maxTargets = 1;
        RelationExpansionException e = assertThrows(RelationExpansionException.class, () -> privileged(() ->
                capped.hydrate(REALM, ItExpandCustomer.class, customers(), List.of("expandShipments"))));
        assertEquals(RelationExpansionException.Code.TOO_MANY_TARGETS, e.code());
    }

    @Test
    void targetsAnswerToTheirOwnAreasRowPolicy() {
        // The caller may read customers; shipments only when open
        ruleContext.addRule(rule("expand-customers", "customer", null).getSecurityURI().getHeader(),
                rule("expand-customers", "customer", null));
        Rule shipments = rule("expand-open-shipments", "shipment", "status:open");
        ruleContext.addRule(shipments.getSecurityURI().getHeader(), shipments);

        List<Document> rows = customers();
        PrincipalContext principal = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("expander@test")
                .withRoles(new String[]{"expander"})
                .withScope("USER")
                .build();
        ResourceContext resource = new ResourceContext.Builder()
                .withRealm(REALM)
                .withArea("expand-it")
                .withFunctionalDomain("customer")
                .withAction("list")
                .build();
        SecurityCallScope.runWithContexts(principal, resource, () -> {
            expansion.hydrate(REALM, ItExpandCustomer.class, rows, List.of("expandShipments"));
            return null;
        });

        assertEquals(List.of("S-OPEN"), refNames(row(rows, withShipments).get("_rel", Document.class)
                .getList("expandShipments", Document.class)), "the closed shipment is hidden by the shipment rule");
    }

    private Rule rule(String name, String domain, String andFilter) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("expander")
                .withArea("expand-it")
                .withFunctionalDomain(domain)
                .withAction("list")
                .build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm(REALM)
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withTenantId("*")
                .withOwnerId("*")
                .build();
        Rule.Builder builder = new Rule.Builder()
                .withName(name)
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withPriority(5)
                .withFinalRule(true);
        if (andFilter != null) builder.withAndFilterString(andFilter);
        return builder.build();
    }

    private <T extends UnversionedBaseModel> T save(T entity, String refName, String status) {
        entity.setRefName(refName);
        entity.setDataDomain(dd);
        if (entity instanceof ItExpandCustomer c) c.setStatus(status);
        if (entity instanceof ItExpandShipment s) s.setStatus(status);
        if (entity instanceof ItExpandNote n) n.setStatus(status);
        privileged(() -> ds.save(entity));
        return entity;
    }

    private void edge(UnversionedBaseModel src, String p, UnversionedBaseModel dst) {
        privileged(() -> edgeRepo.upsert(REALM, dd, src.getClass().getSimpleName(), src.getId().toHexString(), p,
                dst.getClass().getSimpleName(), dst.getId().toHexString(), false, null));
    }

    private List<Document> customers() {
        return ds.getDatabase().getCollection("it_expand_customers").find()
                .sort(new Document("refName", 1)).into(new ArrayList<>());
    }

    private static Document row(List<Document> rows, UnversionedBaseModel entity) {
        return rows.stream().filter(r -> entity.getId().equals(r.get("_id"))).findFirst().orElseThrow();
    }

    private static List<String> refNames(List<Document> docs) {
        return docs.stream().map(d -> d.getString("refName")).sorted().toList();
    }

    private static void privileged(Runnable work) {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            work.run();
        }
    }
}
