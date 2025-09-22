package com.e2eq.framework.model.persistent.morphia;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;

/**
 * Simple POJO returned by CounterRepo when an encoded value is requested.
 * Lives in repo module to be usable outside REST layer.
 */
@RegisterForReflection
@Data
@ToString
@EqualsAndHashCode
@AllArgsConstructor
public class CounterValue {
    private final long value;
    private final Integer base; // nullable if not encoded
    private final String encodedValue; // nullable if not encoded

    public CounterValue(long value) {
        this(value, null, null);
    }




}
