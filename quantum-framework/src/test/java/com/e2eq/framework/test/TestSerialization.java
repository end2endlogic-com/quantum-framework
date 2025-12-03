package com.e2eq.framework.test;

import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import com.e2eq.framework.model.persistent.base.Coordinate;
import com.e2eq.framework.model.persistent.base.MailingAddress;
import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.semver4j.Semver;

import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestSerialization {
    @Inject
    ObjectMapper mapper;
    @Test
    public void testMailingAddressSerialization() throws JsonProcessingException {

        MailingAddress mailingAddress = MailingAddress.builder()
                .city("city")
                .countryTwoLetterCode("US")
                .stateTwoLetterCode("GA")
                .addressLine1("any street 1")
                .zip4("30022")
                .coordinates(Coordinate.builder()
                        .latitude(1.0)
                        .longitude(1.0)
                        .position(new double[]{1.0, 1.0})
                        .build())
                .build();

       String value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mailingAddress);
       System.out.println(value);
       MailingAddress address = mapper.readerFor(MailingAddress.class).readValue(value);
       Assertions.assertTrue(mailingAddress.equals(address));
    }

    @Test
    public void testSemverSerialization() throws JsonProcessingException {
        Semver semver = new Semver("1.0.0");
        String value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(semver);
        System.out.println(value);
    }

    @Test
    public void testDatabaseVersionSerialization() throws JsonProcessingException {
       DatabaseVersion databaseVersion = new DatabaseVersion();
       databaseVersion.setCurrentVersionString("1.0.0");
       databaseVersion.setLastUpdated(new java.util.Date());

        String value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(databaseVersion);
        System.out.println(value);
    }

    public MenuItemModel createMenuItemModel(String refName, String displayName) {
        MenuItemModel menuItem = new MenuItemModel();
        menuItem.setRefName(refName);
        menuItem.setDisplayName(displayName);
        return menuItem;
    }

    @Test
    public void testMenuHierarchySerialization() throws JsonProcessingException {
        MenuItemStaticDynamicList menuItemStaticDynamicList = new MenuItemStaticDynamicList();
        menuItemStaticDynamicList.setItems(List.of(createMenuItemModel("TEST1", "Test 1"), createMenuItemModel("TEST2", "Test 2")));

        String value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(menuItemStaticDynamicList);
        System.out.println(value);
        MenuItemStaticDynamicList slist = mapper.readerFor(MenuItemStaticDynamicList.class).readValue(value);
        Assertions.assertTrue(menuItemStaticDynamicList.equals(slist));

        MenuHierarchyModel menuRootHierarchyModel = new MenuHierarchyModel();
        menuRootHierarchyModel.setRefName("root");
        menuRootHierarchyModel.setDisplayName("Root");
        menuRootHierarchyModel.setStaticDynamicList(menuItemStaticDynamicList);

        MenuHierarchyModel menuChildHierarchyModel = new MenuHierarchyModel();
        menuChildHierarchyModel.setRefName("child");
        menuChildHierarchyModel.setDisplayName("Child");
        menuChildHierarchyModel.setParent(menuRootHierarchyModel.createEntityReference());
        List<MenuHierarchyModel> children = new ArrayList<>();
        children.add(menuChildHierarchyModel);
        menuRootHierarchyModel.setChildren( children);
        MenuItemStaticDynamicList childSlist = new MenuItemStaticDynamicList();
        childSlist.setItems(List.of(createMenuItemModel("TEST3", "Test 3"), createMenuItemModel("TEST4", "Test 4")));
        menuChildHierarchyModel.setStaticDynamicList(childSlist);

        value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(menuRootHierarchyModel);
        System.out.println(value);
        MenuHierarchyModel root = mapper.readerFor(MenuHierarchyModel.class).readValue(value);
        Assertions.assertTrue(menuRootHierarchyModel.equals(root));


    }


}
