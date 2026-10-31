package com.e2eq.framework.test;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Expand target for the QueryGateway / repo aggregation security tests. */
@Entity(value = "sgfExpandTarget", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@FunctionalMapping(area = "TEST", domain = "TEST")
public class SgfExpandTarget extends BaseModel {

    protected String secret;

    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
