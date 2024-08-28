package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@NoArgsConstructor
@ToString
@RegisterForReflection
@Entity
@Data
public  class Coordinate {
    private double[] position = new double[2];
    protected boolean exact= true;
    protected Double latitude;
    protected Double longitude;
}
