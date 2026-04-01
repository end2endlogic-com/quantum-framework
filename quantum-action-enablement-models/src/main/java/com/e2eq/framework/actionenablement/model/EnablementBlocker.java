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
public class EnablementBlocker {

    private EnablementImpact impact;
    private String type;
    private String code;
    private String message;
    private String severity;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
