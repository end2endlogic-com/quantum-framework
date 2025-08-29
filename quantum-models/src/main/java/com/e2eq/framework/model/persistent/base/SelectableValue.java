package com.e2eq.framework.model.persistent.base;

import lombok.*;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public @Data class SelectableValue {
    protected DynamicAttributeType type;
    protected String name;
    protected Object value;
    protected String className;
    protected int sequenceNumber;
}
