package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.rest.dto.GenericHierarchyDto;
import com.e2eq.framework.rest.mappers.HierarchyMapper;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class HierarchyTreeService {

    @Inject
    MorphiaDatastore datastore;

    public <T extends HierarchicalModel<T, O, L>, O extends UnversionedBaseModel, L extends StaticDynamicList<O>>
    GenericHierarchyDto buildTree(ObjectId rootId, Class<T> clazz, int maxDepth) {
        T root = datastore.find(clazz)
                .filter(Filters.eq("_id", rootId))
                .first();
        if (root == null) return null;
        return toDtoRecursive(root, clazz, 0, Math.max(0, maxDepth));
    }

    private <T extends HierarchicalModel<T, O, L>, O extends UnversionedBaseModel, L extends StaticDynamicList<O>>
    GenericHierarchyDto toDtoRecursive(T node, Class<T> clazz, int depth, int maxDepth) {
        List<GenericHierarchyDto> childDtos = List.of();
        if (depth < maxDepth) {
            List<T> children = datastore.find(clazz)
                    .filter(Filters.eq("parent.entityId", node.getId()))
                    .iterator().toList();
            childDtos = children.stream()
                    .map(c -> toDtoRecursive(c, clazz, depth + 1, maxDepth))
                    .collect(Collectors.toList());
        }
        return HierarchyMapper.toDto(node, childDtos);
    }
}
