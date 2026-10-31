package com.e2eq.framework.test;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Expand target in its own functional area whose policy excludes {@code secret}. The no-arg
 * constructor is private (Morphia instantiates it reflectively), so code that needs a public
 * constructor to learn the target's area cannot resolve its policy.
 */
@Entity(value = "sgfSealedTarget", useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@FunctionalMapping(area = "SGFSEAL", domain = "SEALED")
public class SgfSealedTarget extends BaseModel {

    protected String secret;

    private SgfSealedTarget() {
    }

    public static SgfSealedTarget create() {
        return new SgfSealedTarget();
    }

    @Override
    public String bmFunctionalArea() {
        return "SGFSEAL";
    }

    @Override
    public String bmFunctionalDomain() {
        return "SEALED";
    }
}
