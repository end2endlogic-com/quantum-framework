package com.e2eq.ontology.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OntologyReindexerPackPinTest {

    @Test
    void packIdDerivesFromObservedSourcePath() {
        assertEquals("ontology", OntologyReindexer.packIdFromSource("/ontology.yaml"));
        assertEquals("orders-core", OntologyReindexer.packIdFromSource("/opt/app/packs/orders-core.yaml"));
        assertEquals("orders-core", OntologyReindexer.packIdFromSource("C:\\packs\\orders-core.yml"));
        assertEquals("default", OntologyReindexer.packIdFromSource("<none>"));
        assertEquals("default", OntologyReindexer.packIdFromSource(null));
        assertEquals("default", OntologyReindexer.packIdFromSource(""));
    }
}
