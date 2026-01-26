package com.e2eq.framework.model.persistent.imports;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for JSON_COLUMN dynamic attribute import strategy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@RegisterForReflection
public class JsonColumnConfig {

    /**
     * The CSV column name containing JSON dynamic attributes.
     * Default: "dynamicAttributes"
     */
    @Builder.Default
    private String columnName = "dynamicAttributes";

    /**
     * JSON format style.
     * COMPACT: {"setName":{"attrName":value}} - values only, types inferred
     * FULL: [{"name":"setName","attributes":[{"name":"attrName","type":"String","value":"x"}]}]
     */
    @Builder.Default
    private JsonFormatStyle formatStyle = JsonFormatStyle.COMPACT;

    /**
     * Allow relaxed JSON parsing (single quotes, unquoted keys).
     * Default: true
     */
    @Builder.Default
    private boolean relaxedParsing = true;
}
