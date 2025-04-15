package com.e2eq.framework.test;

import com.e2eq.framework.annotations.AuditPersistence;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AuditPersistence
public class TestParentModel extends BaseModel {

    protected String testField;
    protected String testField2;
    protected String testField3;

    protected Map<String, String> testMap;

    protected List<DynamicAttributeSet> dynamicAttributeSets = new ArrayList<>();

    @Override
    public String bmFunctionalArea() {
        return "QUANTUM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
