package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
public class TestCSVModel extends BaseModel {

    protected String testField1;
    protected List<String> testList;
    protected Map<String, String> testMap;


    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CSV";
    }
}
