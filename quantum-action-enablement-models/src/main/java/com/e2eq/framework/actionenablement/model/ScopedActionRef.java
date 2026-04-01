package com.e2eq.framework.actionenablement.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ScopedActionRef {

    private String area;
    private String functionalDomain;
    private String action;

    public static String normalizeSegment(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    @JsonIgnore
    public String normalizedArea() {
        return normalizeSegment(area);
    }

    @JsonIgnore
    public String normalizedFunctionalDomain() {
        return normalizeSegment(functionalDomain);
    }

    @JsonIgnore
    public String normalizedAction() {
        return normalizeSegment(action);
    }

    @JsonIgnore
    public String toUriString() {
        return normalizedArea() + "/" + normalizedFunctionalDomain() + "/" + normalizedAction();
    }
}
