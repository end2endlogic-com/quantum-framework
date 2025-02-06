package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode
@ToString
public class TestBookModel extends BaseModel {

    protected String title;

    @Reference
    protected TestAuthorModel author;

    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
