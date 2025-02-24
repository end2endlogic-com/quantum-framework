package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Data
@EqualsAndHashCode (callSuper = true)
@RegisterForReflection
@ToString
public class TestAuthorModel extends BaseModel {

    protected String authorName;

    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
