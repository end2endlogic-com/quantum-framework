package com.e2eq.framework.model.persistent.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ProjectionField {
    public enum ProjectionType {
        INCLUDE,
        EXCLUDE
    }
    protected String fieldName;
    protected ProjectionType projectionType = ProjectionType.INCLUDE;
}
