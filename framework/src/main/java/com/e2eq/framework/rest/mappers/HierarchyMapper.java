package com.e2eq.framework.rest.mappers;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.rest.dto.GenericHierarchyDto;

import java.util.List;

public final class HierarchyMapper {
    private HierarchyMapper() {}

    public static <T extends HierarchicalModel<?, ?, ?>> GenericHierarchyDto toDto(T model, List<GenericHierarchyDto> childDtos) {
        GenericHierarchyDto dto = new GenericHierarchyDto();
        dto.id = model.getId() != null ? model.getId().toHexString() : null;
        dto.refName = model.getRefName();
        dto.displayName = model.getDisplayName();
        dto.staticDynamicList = model.getStaticDynamicList();
        dto.children = childDtos;
        return dto;
    }
}
