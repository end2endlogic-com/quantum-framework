package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.annotations.RelationType;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;

import java.util.ArrayList;
import java.util.List;

/**
 * Test entity representing a hierarchical Territory.
 *
 * <p>Territories form a hierarchy (parentTerritory) and contain locations.
 * An associate assigned to a territory should implicitly have access to
 * all locations in that territory and its sub-territories.</p>
 *
 * <p>Key relationships:</p>
 * <ul>
 *   <li>parentTerritory: self-referential hierarchy (Territory -> Territory)</li>
 *   <li>containsLocations: explicit locations in this territory (Territory -> TerritoryLocation[])</li>
 * </ul>
 */
@Entity(value = "it_territories")
@OntologyClass(id = "Territory")
public class Territory extends UnversionedBaseModel {

    private String name;
    private String description;

    @Reference
    @OntologyProperty(id = "parentTerritory", ref = "Territory", relation = RelationType.MANY_TO_ONE)
    private Territory parentTerritory;

    @Reference
    @OntologyProperty(id = "containsLocations", ref = "TerritoryLocation", relation = RelationType.ONE_TO_MANY)
    private List<TerritoryLocation> locations = new ArrayList<>();

    public Territory() {}

    public Territory(String refName, String name) {
        setRefName(refName);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Territory getParentTerritory() {
        return parentTerritory;
    }

    public void setParentTerritory(Territory parentTerritory) {
        this.parentTerritory = parentTerritory;
    }

    public List<TerritoryLocation> getLocations() {
        return locations;
    }

    public void setLocations(List<TerritoryLocation> locations) {
        this.locations = locations;
    }

    public void addLocation(TerritoryLocation location) {
        if (this.locations == null) {
            this.locations = new ArrayList<>();
        }
        this.locations.add(location);
    }

    @Override
    public String bmFunctionalArea() {
        return "territory-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "territory";
    }
}
