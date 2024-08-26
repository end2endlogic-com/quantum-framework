package com.e2eq.framework.model.persistent.base;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;

@EqualsAndHashCode
@NoArgsConstructor
@Data
public class DynamicAttributeSet {
    protected String name;
    protected LinkedHashMap<String, DynamicAttribute> attributes;
}
