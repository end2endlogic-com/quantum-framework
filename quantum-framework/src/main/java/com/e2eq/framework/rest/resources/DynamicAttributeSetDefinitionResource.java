package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.DynamicAttributeSetDefinition;
import com.e2eq.framework.model.persistent.morphia.DynamicAttributeSetDefinitionRepo;
import jakarta.ws.rs.Path;

@Path("/integration/dynamicAttributeSetDefinitions")
public class DynamicAttributeSetDefinitionResource extends BaseResource<DynamicAttributeSetDefinition, DynamicAttributeSetDefinitionRepo> {
    protected DynamicAttributeSetDefinitionResource(DynamicAttributeSetDefinitionRepo repo) {
        super(repo);
    }
}
