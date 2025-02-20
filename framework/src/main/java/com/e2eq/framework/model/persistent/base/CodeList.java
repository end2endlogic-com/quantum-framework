package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@EqualsAndHashCode (callSuper = true)
@ToString
@RegisterForReflection
@Entity
@NoArgsConstructor
public class CodeList extends BaseModel {
    @NonNull
    @NotNull
    String category="default";

    @NonNull
    @NotNull
    String key;

    String description;

    @NonNull
    @NotNull
    String valueType="STRING";
    List<Object> values;

    @Override
    public String bmFunctionalArea() {
        return "INTEGRATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CODE_LISTS";
    }
}
