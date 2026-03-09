package com.e2eq.framework.model.general;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Entity
@Data
@RegisterForReflection
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class MenuHierarchyModel extends HierarchicalModel<MenuHierarchyModel, MenuItemModel, MenuItemStaticDynamicList> {
    @Override
    @Schema(implementation = MenuHierarchyModel.class, description = "Children of the menu hierarchy node")
    public List<MenuHierarchyModel> getChildren() {
        return super.getChildren();
    }

    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
