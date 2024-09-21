package com.e2eq.framework.model.persistent.base;

import lombok.*;
import org.checkerframework.checker.units.qual.N;

import java.util.List;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public @Data class DynamicAttribute {
    protected String id;
    protected String name;
    protected String label;
    protected String description;
    protected DynamicAttributeType type;
    protected Object value;
    protected Object defaultValue;
    @Builder.Default
    boolean required=false;
    @Builder.Default
    boolean inheritable=false;
    @Builder.Default
    boolean hidden=false;
    @Builder.Default
    boolean caseSensitive=false;

    protected String className;
    protected List<SelectableValue> selectValues;


}
