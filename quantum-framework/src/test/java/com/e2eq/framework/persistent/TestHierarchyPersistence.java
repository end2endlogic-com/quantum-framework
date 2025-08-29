package com.e2eq.framework.persistent;

import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.MenuHierarchyModel;
import com.e2eq.framework.test.MenuItemModel;
import com.e2eq.framework.test.MenuItemStaticDynamicList;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.*;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestHierarchyPersistence extends BaseRepoTest {
    @Inject
    MenuHierarchyRepo hierarchyRepo;

    @Inject
    MenuItemRepo menuItemRepo;

    @Inject
    MenuItemStaticDynamicListRepo menuStaticDynamicListRepo;

    protected MenuHierarchyModel getOrCreateHierarchy(String name) {
        Optional<MenuHierarchyModel> oroot = hierarchyRepo.findByRefName(name);
        MenuHierarchyModel root;
        if (!oroot.isPresent()) {
            root = new MenuHierarchyModel();
            root.setRefName(name);
            root = hierarchyRepo.save(root);
        } else {
            root = oroot.get();
        }
        return root;
    }

    public MenuItemStaticDynamicList getOrCreateStaticDynamicList(String name, List<MenuItemModel> items) {
        MenuItemStaticDynamicList slist = new MenuItemStaticDynamicList();
        slist.setRefName(name);
        slist.setItems(items);
        return slist;
    }

    MenuItemModel getOrCreateMenuItem(String name) {
        Optional<MenuItemModel> omenuItemModel = menuItemRepo.findByRefName(name);
        if (omenuItemModel.isPresent()) {
            return omenuItemModel.get();
        } else {
            MenuItemModel menuItemModel = new MenuItemModel();
            menuItemModel.setRefName(name);
            menuItemModel = menuItemRepo.save(menuItemModel);
            return menuItemModel;
        }
    }
    public List<MenuItemModel> getOrCreateMenuItems(int start, int end) {
        List<MenuItemModel> menuItems = new ArrayList<>();
        for (int i = start; i < end; i++) {
           MenuItemModel model = getOrCreateMenuItem("item" + i);
           menuItems.add(model);
        }
        return menuItems;
    }
    @Test
    public void testMenuHierarchyPersistence() {

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            // create a few Menu Items
            List<MenuItemModel>  menuItems =  getOrCreateMenuItems(0,10);


            // Create a MenuItemStaticDynamicList
            MenuItemStaticDynamicList menuItemStaticDynamicList = getOrCreateStaticDynamicList("rootSList", menuItems);

            // create the root
            MenuHierarchyModel menuRootHierarchyModel = getOrCreateHierarchy("root");
            menuRootHierarchyModel.setStaticDynamicList(menuItemStaticDynamicList);

            // create descendants
            MenuHierarchyModel menuChildHierarchyModel = getOrCreateHierarchy("child");
            menuChildHierarchyModel.setParent(menuRootHierarchyModel.createEntityReference());
            MenuItemStaticDynamicList childSlist = getOrCreateStaticDynamicList("childSlist", getOrCreateMenuItems(10,15));
            menuChildHierarchyModel.setStaticDynamicList(childSlist);
            hierarchyRepo.save(menuChildHierarchyModel);

            menuRootHierarchyModel = hierarchyRepo.findByRefName("root").orElse(null);

            List<MenuItemModel> items = hierarchyRepo.getAllObjectsForHierarchy(menuRootHierarchyModel.getId());

            for (MenuItemModel item : items) {
                System.out.println(item);
            }
        }


    }
}
