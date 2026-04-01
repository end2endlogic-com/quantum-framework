package com.e2eq.framework.actionenablement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DependencyCheckRef {

    private String type;
    private String refName;

    @Builder.Default
    private Map<String, Object> config = new LinkedHashMap<>();

    public String normalizedType() {
        return type == null ? "" : type.trim().toLowerCase();
    }
}
