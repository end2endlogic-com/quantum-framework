package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity(value = "multihop_customers", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class MultiHopCustomer extends BaseModel {
    protected String customerName;

    @ReferenceTarget(target = MultiHopOrder.class, collection = "multihop_orders")
    protected EntityReference orderRef;

    @Override
    public String bmFunctionalArea() {
        return "CRM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CUSTOMERS";
    }
}
