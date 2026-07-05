package com.e2eq.ontology.model;

import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the persistence round-trip of class-level presentation + governance metadata through
 * both persisted TBox entities. Without this, {@code toTBox()} rebuilt ClassDefs with the 4-arg
 * constructor and silently dropped {@code label}/{@code aliases}/{@code metadata} — so an admitted
 * class's {@code functionalArea}/{@code functionalDomain} never reached OntologyResourceResolver
 * (Phase 3 of ontology federation).
 */
class TBoxMetadataRoundTripTest {

    private static TBox oneClassWithGovernance() {
        ClassDef cd = new ClassDef("Customer", Set.of(), Set.of(), Set.of(),
                "Customer", Set.of("Client"),
                Map.of("functionalArea", "crm", "functionalDomain", "customer", "defaultReadAction", "view"));
        // A relation carrying a join-key in its metadata (what Tier 2/3 reads).
        PropertyDef pd = new PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"),
                false, Optional.of("places"), false, false, true, Set.of(), false,
                "placed by", Set.of(),
                Map.of("joinFromField", "customerId", "joinToField", "id"));
        return new TBox(Map.of("Customer", cd), Map.of("placedBy", pd), List.of());
    }

    private static void assertGovernanceSurvives(TBox rt) {
        ClassDef out = rt.classes().get("Customer");
        assertNotNull(out, "Customer class must survive round-trip");
        assertEquals("crm", out.metadata().get("functionalArea"));
        assertEquals("customer", out.metadata().get("functionalDomain"));
        assertEquals("view", out.metadata().get("defaultReadAction"));
        assertEquals("Customer", out.label());
        assertTrue(out.aliases().contains("Client"));

        PropertyDef pOut = rt.properties().get("placedBy");
        assertNotNull(pOut, "placedBy property must survive round-trip");
        assertEquals("customerId", pOut.metadata().get("joinFromField"));
        assertEquals("id", pOut.metadata().get("joinToField"));
        assertEquals("placed by", pOut.label());
    }

    @Test
    void ontologyTBox_roundTripsClassMetadata() {
        OntologyTBox doc = new OntologyTBox(oneClassWithGovernance(), "h1", "y1", "test");
        assertGovernanceSurvives(doc.toTBox());
    }

    @Test
    void tenantOntologyTBox_roundTripsClassMetadata() {
        TenantOntologyTBox doc = new TenantOntologyTBox(oneClassWithGovernance(), "h1", "y1", "test", "1.0");
        assertGovernanceSurvives(doc.toTBox());
    }
}
