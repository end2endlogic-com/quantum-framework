package com.e2eq.framework.model.persistent.base;


import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public @Data class SortParameter {
    public enum SortOrderEnum
    {
        DESC,
        ASC
    }
    @NotNull
    protected String fieldName;
    @NotNull
    protected SortOrderEnum direction;
}
