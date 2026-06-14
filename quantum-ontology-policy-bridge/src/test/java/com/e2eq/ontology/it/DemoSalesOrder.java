package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Fulfillment collaboration demo: a sales order with commercially sensitive fields. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "demo_sales_orders")
public class DemoSalesOrder extends UnversionedBaseModel {

    private String shipTo;
    private String status;
    private Double unitPrice; // commercially sensitive: hidden from carriers
    private Double margin;    // most sensitive: owner-only

    @Override
    public String bmFunctionalArea() { return "FULFILLMENT"; }

    @Override
    public String bmFunctionalDomain() { return "SALES_ORDER"; }
}
