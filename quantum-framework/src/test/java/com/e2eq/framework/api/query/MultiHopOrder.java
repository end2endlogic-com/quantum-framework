package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity(value = "multihop_orders", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class MultiHopOrder extends BaseModel {
    protected String orderNumber;

    @ReferenceTarget(target = MultiHopSupplier.class, collection = "multihop_suppliers")
    protected EntityReference supplierRef;

    @Override
    public String bmFunctionalArea() {
        return "SALES";
    }

    @Override
    public String bmFunctionalDomain() {
        return "ORDERS";
    }
}
