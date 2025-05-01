package com.e2eq.framework.model.persistent.base;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@EqualsAndHashCode
@NoArgsConstructor
@Data
public class DynamicAttributeSet {
    protected String name;
    protected List< DynamicAttribute> attributes;
}
