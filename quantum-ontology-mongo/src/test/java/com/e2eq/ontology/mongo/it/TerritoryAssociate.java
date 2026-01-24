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
 * Test entity representing an associate (user/employee) who can be assigned to territories.
 *
 * <p>Associates can be assigned to one or more territories. Through the computed edge
 * provider, the associate implicitly gains "canSeeLocation" edges to all locations
 * within their assigned territories (including sub-territories).</p>
 *
 * <p>Key relationships:</p>
 * <ul>
 *   <li>assignedTerritories: explicit assignment (Associate -> Territory[])</li>
 *   <li>canSeeLocation: implied/computed (Associate -> TerritoryLocation[]) - via ComputedEdgeProvider</li>
 * </ul>
 */
@Entity(value = "it_territory_associates")
@OntologyClass(id = "TerritoryAssociate")
public class TerritoryAssociate extends UnversionedBaseModel {

    private String firstName;
    private String lastName;
    private String email;

    @Reference
    @OntologyProperty(id = "assignedTerritories", ref = "Territory", relation = RelationType.MANY_TO_MANY)
    private List<Territory> assignedTerritories = new ArrayList<>();

    public TerritoryAssociate() {}

    public TerritoryAssociate(String refName, String firstName, String lastName, String email) {
        setRefName(refName);
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<Territory> getAssignedTerritories() {
        return assignedTerritories;
    }

    public void setAssignedTerritories(List<Territory> assignedTerritories) {
        this.assignedTerritories = assignedTerritories;
    }

    public void addTerritory(Territory territory) {
        if (this.assignedTerritories == null) {
            this.assignedTerritories = new ArrayList<>();
        }
        this.assignedTerritories.add(territory);
    }

    @Override
    public String bmFunctionalArea() {
        return "territory-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "associate";
    }
}
