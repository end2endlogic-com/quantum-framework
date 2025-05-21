package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

@Data
@RegisterForReflection
@Entity
@EqualsAndHashCode(callSuper = true)
@ToString
public class MenuItemStaticDynamicList extends StaticDynamicList<MenuItemModel> {

    @Reference(ignoreMissing = true  )
    protected List<MenuItemModel> menuItems;


    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }

    @Override
    public List<MenuItemModel> getItems() {
        return menuItems;
    }

    @Override
    public void setItems(List<MenuItemModel> items) {
        this.setMode(Mode.STATIC);
        this.setFilterString(null);
        this.menuItems = items;
    }
}
