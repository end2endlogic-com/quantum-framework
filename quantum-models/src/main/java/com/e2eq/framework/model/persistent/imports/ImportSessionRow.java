package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
@Entity
@Indexes({
        @Index(fields = {@Field(value = "sessionRefName")}, options = @IndexOptions()),
        @Index(fields = {@Field(value = "rowNumber")}, options = @IndexOptions())
})
public class ImportSessionRow extends BaseModel {
    private String sessionRefName; // ImportSession.refName
    private int rowNumber;
    private String intent; // INSERT, UPDATE, SKIP
    private boolean hasErrors;

    // JSON-serialized List<CSVImportHelper.FieldError>
    private String errorsJson;

    // JSON-serialized T (target entity)
    private String recordJson;

    // Original CSV line
    private String rawLine;

    @Override
    public String bmFunctionalArea() {
        return "IMPORTS";
    }

    @Override
    public String bmFunctionalDomain() {
        return "IMPORTS";
    }
}
