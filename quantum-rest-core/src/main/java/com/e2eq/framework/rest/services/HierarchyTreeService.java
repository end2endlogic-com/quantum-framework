package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.HierarchicalRepo;
import com.e2eq.framework.rest.dto.GenericHierarchyDto;
import com.e2eq.framework.rest.mappers.HierarchyMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class HierarchyTreeService {

    public <T extends HierarchicalModel<T, O, L>, O extends UnversionedBaseModel, L extends StaticDynamicList<O>>
    GenericHierarchyDto buildTree(ObjectId rootId, HierarchicalRepo<T, O, L, ?, ?> repo, int maxDepth) {
        T root = repo.findById(rootId).orElse(null);
        if (root == null) return null;
        return toDtoRecursive(root, repo, 0, Math.max(0, maxDepth));
    }

    private <T extends HierarchicalModel<T, O, L>, O extends UnversionedBaseModel, L extends StaticDynamicList<O>>
    GenericHierarchyDto toDtoRecursive(T node, HierarchicalRepo<T, O, L, ?, ?> repo, int depth, int maxDepth) {
        List<GenericHierarchyDto> childDtos = List.of();
        if (depth < maxDepth) {
            List<T> children = new ArrayList<>();
            if (node.getDescendants() != null && !node.getDescendants().isEmpty()) {
                for (ObjectId childId : node.getDescendants()) {
                    repo.findById(childId).ifPresent(children::add);
                }
            }

            childDtos = new ArrayList<>(children.size());
            for (T child : children) {
                childDtos.add(toDtoRecursive(child, repo, depth + 1, maxDepth));
            }
        }
        return HierarchyMapper.toDto(node, childDtos);
    }
}
