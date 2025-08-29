package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.ProjectionField;

import java.util.ArrayList;
import java.util.List;

public class FilterUtils {
    public static List<ProjectionField> convertProjectionFields(String projection) {
        List<ProjectionField> projectionFields = new ArrayList<>();
        if (projection != null) {
            for (String projectionPart : projection.split(",")) {
                String cleanProjectionPart = projectionPart.trim();
                if (cleanProjectionPart.startsWith("-")) {
                    projectionFields.add(new ProjectionField(cleanProjectionPart.substring(1),
                            ProjectionField.ProjectionType.EXCLUDE));
                } else if (cleanProjectionPart.startsWith("+")) {
                    projectionFields.add(new ProjectionField(
                            cleanProjectionPart.substring(1),
                            ProjectionField.ProjectionType.INCLUDE));
                } else {
                    projectionFields.add(new ProjectionField(cleanProjectionPart,
                            ProjectionField.ProjectionType.INCLUDE));
                }
            }
        }
        return projectionFields;
    }
}
