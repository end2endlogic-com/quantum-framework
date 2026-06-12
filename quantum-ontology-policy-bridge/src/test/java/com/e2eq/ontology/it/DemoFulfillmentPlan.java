package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Fulfillment collaboration demo: a plan fulfilling an order, worked by carrier + supplier orgs. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "demo_fulfillment_plans")
public class DemoFulfillmentPlan extends UnversionedBaseModel {

    private String status;
    private String route;

    @Override
    public String bmFunctionalArea() { return "FULFILLMENT"; }

    @Override
    public String bmFunctionalDomain() { return "FULFILLMENT_PLAN"; }
}
