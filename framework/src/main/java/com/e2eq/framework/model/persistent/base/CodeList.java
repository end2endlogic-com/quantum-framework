package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Data
@EqualsAndHashCode (callSuper = true)
@ToString
@RegisterForReflection
@Entity
@NoArgsConstructor
public class CodeList extends BaseModel {
    String description;
    String valueType;
    List<Object> values;

    @Override
    public String bmFunctionalArea() {
        return "PSA";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CODE_LISTS";
    }
}
