package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode (callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
public @Data class CounterResponse extends ResponseBase {
       long value;
       Integer base; // optional, if provided indicates the base used for encodedValue
       String encodedValue; // optional, representation of value in the given base

       public CounterResponse(long value) {
           this.value = value;
       }
   }
