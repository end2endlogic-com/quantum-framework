package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

/** ItExpandNote for {@code OntologyRelationExpansionTest}: no {@code @FunctionalMapping}, so it cannot be governed. */
@Entity(value = "it_expand_notes")
@OntologyClass(id = "ItExpandNote")
public class ItExpandNote extends UnversionedBaseModel {

    private String status;
    private String secret;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    @Override
    public String bmFunctionalArea() {
        return "expand-it";
    }

    @Override
    public String bmFunctionalDomain() {
        return "note";
    }
}
