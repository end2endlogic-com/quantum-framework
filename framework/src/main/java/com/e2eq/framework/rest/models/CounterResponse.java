package com.e2eq.framework.rest.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode( callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
public @Data class CounterResponse extends ResponseBase {
       long value;
   }