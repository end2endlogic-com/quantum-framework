package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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

            List<MenuHierarchyModel> visited = new ArrayList<>();
            hierarchyRepo.visitHierarchy(root.getId(), visited::add);

            Assertions.assertEquals(2, visited.size());
            Assertions.assertTrue(visited.contains(child1));
            Assertions.assertTrue(visited.contains(child2));
        }
    }

    @Test
    public void testFilteredMenu() {
        try (SecuritySession ss = new SecuritySession(pContext, rContext)) {
            MenuHierarchyModel root = new MenuHierarchyModel();
            root.setRefName("filterRoot");
            root = hierarchyRepo.save(root);

            MenuHierarchyModel child1 = new MenuHierarchyModel();
            child1.setRefName("filterChild1");
            child1.setParent(root.createEntityReference());
            child1 = hierarchyRepo.save(child1);

            MenuHierarchyModel child2 = new MenuHierarchyModel();
            child2.setRefName("filterChild2");
            child2.setParent(root.createEntityReference());
            child2 = hierarchyRepo.save(child2);

            MenuHierarchyModel child3 = new MenuHierarchyModel();
            child3.setRefName("filterChild3");
            child3.setParent(child2.createEntityReference());
            child3 = hierarchyRepo.save(child3);

            MenuHierarchyModel filtered = hierarchyRepo.getFilteredMenu(root.getId().toHexString(), List.of("identity1", "identity2"));

            Assertions.assertNotNull(filtered);
        }
    }
}
