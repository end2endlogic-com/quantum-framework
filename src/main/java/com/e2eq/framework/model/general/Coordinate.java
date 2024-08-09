package com.e2eq.framework.model.general;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@NoArgsConstructor
@ToString
public @Data class Coordinate {
    private double[] position = new double[2];
    protected boolean exact;
}
