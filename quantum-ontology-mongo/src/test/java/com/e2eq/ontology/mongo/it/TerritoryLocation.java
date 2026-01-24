package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

/**
 * Test entity representing a physical location.
 * Locations can be assigned to territories.
 */
@Entity(value = "it_territory_locations")
@OntologyClass(id = "TerritoryLocation")
public class TerritoryLocation extends UnversionedBaseModel {

    private String name;
    private String city;
    private String state;

    public TerritoryLocation() {}

    public TerritoryLocation(String refName, String name, String city, String state) {
        setRefName(refName);
        this.name = name;
        this.city = city;
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    @Override
    public String bmFunctionalArea() {
        return "territory-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "location";
    }
}
