package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemStaticDynamicListRepo;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestHierarchyPersistence extends BaseRepoTest {
    @Inject
    MenuHierarchyRepo menuHierarchyRepo;

    @Inject
    MenuItemRepo menuItemRepo;

    @Inject
    MenuItemStaticDynamicListRepo menuStaticDynamicListRepo;

    protected MenuHierarchyModel getOrCreateHierarchy(String name) {
        Optional<MenuHierarchyModel> oroot = menuHierarchyRepo.findByRefName(name);
        MenuHierarchyModel root;
        if (!oroot.isPresent()) {
            root = new MenuHierarchyModel();
            root.setRefName(name);
            root = menuHierarchyRepo.save(root);
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
            menuHierarchyRepo.save(menuChildHierarchyModel);

            menuRootHierarchyModel = menuHierarchyRepo.findByRefName("root").orElse(null);

            List<MenuItemModel> items = menuHierarchyRepo.getAllObjectsForHierarchy(menuRootHierarchyModel.getId());

            for (MenuItemModel item : items) {
                System.out.println(item);
            }
        }


    }
}
