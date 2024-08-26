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
    boolean required=false;
    boolean inheritable=false;
    boolean hidden=false;
    boolean caseSensitive=false;

    protected String className;
    protected List<SelectableValue> selectValues;


}
