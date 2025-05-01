package com.e2eq.framework.model.persistent.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public @Data class ProjectionParameter {
    public enum ProjectionType {
        INCLUDE,
        EXCLUDE
    }
    protected String fieldName;
    protected ProjectionField.ProjectionType projectionType = ProjectionField.ProjectionType.INCLUDE;
}
