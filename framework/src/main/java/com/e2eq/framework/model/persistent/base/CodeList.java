package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexes;
import io.quarkus.arc.All;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@EqualsAndHashCode (callSuper = true)
@ToString
@RegisterForReflection
@Entity
@NoArgsConstructor
@SuperBuilder
@AllArgsConstructor
@Indexes({
        @Index(fields = {@dev.morphia.annotations.Field("category"), @dev.morphia.annotations.Field("key"), @dev.morphia.annotations.Field("dataDomain")}, options = @dev.morphia.annotations.IndexOptions(unique = true))
})
public class CodeList extends BaseModel {
    @NonNull
    @NotNull(message = "category is required")
    @Builder.Default
    String category="default";

    @NonNull
    @NotNull(message="key is required")
    String key;

    String description;

    @NonNull
    @NotNull
    @Builder.Default
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
