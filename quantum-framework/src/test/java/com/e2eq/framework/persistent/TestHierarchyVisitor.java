package com.e2eq.framework.persistent;

import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.test.MenuHierarchyModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestHierarchyVisitor extends BaseRepoTest {

    @Inject
    MenuHierarchyRepo hierarchyRepo;

    @Test
    public void testVisitHierarchy() {
        try (SecuritySession ss = new SecuritySession(pContext, rContext)) {
            MenuHierarchyModel root = new MenuHierarchyModel();
            root.setRefName("visitRoot");
            root = hierarchyRepo.save(root);

            MenuHierarchyModel child1 = new MenuHierarchyModel();
            child1.setRefName("visitChild1");
            child1.setParent(root.createEntityReference());
            child1 = hierarchyRepo.save(child1);

            MenuHierarchyModel child2 = new MenuHierarchyModel();
            child2.setRefName("visitChild2");
            child2.setParent(root.createEntityReference());
            child2 = hierarchyRepo.save(child2);

            List<ObjectId> visited = new ArrayList<>();
            hierarchyRepo.visitHierarchy(root.getId(), visited::add);

            Assertions.assertEquals(2, visited.size());
            Assertions.assertTrue(visited.contains(child1.getId()));
            Assertions.assertTrue(visited.contains(child2.getId()));
        }
    }
}
