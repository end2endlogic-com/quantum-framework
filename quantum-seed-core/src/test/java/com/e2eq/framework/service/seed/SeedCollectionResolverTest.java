package com.e2eq.framework.service.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedCollectionResolverTest {

    @Test
    void derivesMenuHierarchyCollectionFromModelClassWhenEntityAnnotationUsesDefaultMarker() {
        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setModelClass("com.e2eq.framework.model.general.MenuHierarchyModel");

        SeedCollectionResolver.ensureCollectionSet(dataset);

        assertEquals("menuHierarchyModel", dataset.getCollection());
    }

    @Test
    void replacesMorphiaDefaultCollectionMarker() {
        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setModelClass("com.e2eq.framework.model.general.MenuHierarchyModel");
        dataset.setCollection(".");

        SeedCollectionResolver.ensureCollectionSet(dataset);

        assertEquals("menuHierarchyModel", dataset.getCollection());
    }

    @Test
    void lookupNamesIncludeLegacyMorphiaDefaultMarkerForExistingHistoryRows() {
        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setModelClass("com.e2eq.framework.model.general.MenuHierarchyModel");

        List<String> names = SeedCollectionResolver.lookupCollectionNames(dataset);

        assertTrue(names.contains("menuHierarchyModel"));
        assertTrue(names.contains("."));
    }
}
