package com.e2eq.framework.test;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Expand root for the QueryGateway / repo aggregation security tests. {@code author} is a managed
 * reference the target does not have, so a rule filter that names it lowers for the root but not
 * for the target.
 */
@Entity(value = "sgfExpandRoot", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@FunctionalMapping(area = "TEST", domain = "TEST")
public class SgfExpandRoot extends BaseModel {

    protected String label;

    @Reference(ignoreMissing = true)
    protected AuthorModel author;

    @ReferenceTarget(target = SgfExpandTarget.class, collection = "sgfExpandTarget")
    protected EntityReference targetRef;

    @ReferenceTarget(target = SgfSealedTarget.class, collection = "sgfSealedTarget")
    protected EntityReference sealedRef;

    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
