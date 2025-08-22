package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@EqualsAndHashCode
@NoArgsConstructor
@Data
@Entity
public class DynamicAttributeSet {
    protected String name;
    protected List< DynamicAttribute> attributes;
}
