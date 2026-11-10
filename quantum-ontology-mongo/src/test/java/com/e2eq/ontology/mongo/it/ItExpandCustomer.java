package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

/** ItExpandCustomer for {@code OntologyRelationExpansionTest}. */
@Entity(value = "it_expand_customers")
@OntologyClass(id = "ItExpandCustomer")
@FunctionalMapping(area = "expand-it", domain = "customer")
public class ItExpandCustomer extends UnversionedBaseModel {

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
        return "customer";
    }
}
