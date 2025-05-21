package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Data
@RegisterForReflection
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class MenuHierarchyModel extends HierarchicalModel<MenuHierarchyModel, MenuItemModel, MenuItemStaticDynamicList> {
    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
