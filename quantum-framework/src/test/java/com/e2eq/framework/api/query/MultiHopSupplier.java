package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity(value = "multihop_suppliers", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class MultiHopSupplier extends BaseModel {
    protected String supplierName;
    protected Double wholesaleCost;
    protected String taxId;

    @Override
    public String bmFunctionalArea() {
        return "PROCUREMENT";
    }

    @Override
    public String bmFunctionalDomain() {
        return "SUPPLIERS";
    }
}
