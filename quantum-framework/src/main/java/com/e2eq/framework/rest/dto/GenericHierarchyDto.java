package com.e2eq.framework.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Read-only DTO for returning hierarchical structures with optional children expansion.
 * Keeps payloads lean by omitting empty/absent fields.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class GenericHierarchyDto {
    public String id;
    public String refName;
    public String displayName;

    // Keep type flexible for framework usage; concrete apps can specialize with their own DTOs
    public Object staticDynamicList;

    public List<GenericHierarchyDto> children;
}
