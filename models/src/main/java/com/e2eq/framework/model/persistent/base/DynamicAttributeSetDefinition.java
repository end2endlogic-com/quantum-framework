package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
public class DynamicAttributeSetDefinition extends BaseModel{
    List<DynamicAttributeDefinition> attributeDefinitions;

    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "DYNAMIC_ATTRIBUTE_SET";
    }
}
