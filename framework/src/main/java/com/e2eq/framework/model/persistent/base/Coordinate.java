package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode
@NoArgsConstructor
@ToString
@RegisterForReflection
@Entity
@Data
@SuperBuilder
public  class Coordinate {
    private double[] position = new double[2];
    protected boolean exact= true;

    @NonNull
    @NotNull(message = "longitude is mandatory")
    protected Double longitude;
    @NonNull
    @NotNull(message = "latitude is mandatory")
    protected Double latitude;

    public Coordinate(Double longitude, Double latitude) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.position[0] = longitude;
        this.position[1] = latitude;
    }


    public void setLongitude(Double longitude) {
        this.longitude = longitude;
        this.position[0] = longitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
        this.position[1] = latitude;
    }

    public void setPosition(double[] position) {
        this.position = position;
        this.longitude = position[0];
        this.latitude = position[1];
    }

}
