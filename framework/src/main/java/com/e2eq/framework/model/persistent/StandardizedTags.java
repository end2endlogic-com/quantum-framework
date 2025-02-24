package com.e2eq.framework.model.persistent;

import com.e2eq.framework.model.persistent.base.BaseCollection;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.google.common.collect.SetMultimap;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Set;

@Data
@Entity
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class StandardizedTags extends BaseModel {

    @NonNull
    @NotNull
    @NotEmpty
    Set<String> values;

    @Override
    public String bmFunctionalArea() {
        return "INTEGRATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "STANDARDIZED_TAGS";
    }
}
