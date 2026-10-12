package com.e2eq.framework.service.provisioning;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TenantProvisioningWorkflowValidationTest {
    TenantProvisioningWorkflowDefaults defaults() { var d=new TenantProvisioningWorkflowDefaults(); d.objectMapper=new ObjectMapper(); return d; }
    @Test void malformedDefinitionCannotExecuteDisplayFallback() {
        var d=defaults();
        for(String json:new String[]{"{", "{}", "{\"steps\":[]}", "{\"steps\":[7]}", "{\"steps\":[{\"key\":\"realm_catalog\",\"required\":\"true\"}]}"})
            assertThrows(IllegalArgumentException.class,()->d.validateSteps(json));
    }
    @Test void verificationCannotBeMovedBeforeInitialization() throws Exception {
        var d=defaults(); var tree=d.objectMapper.readTree(d.buildDefinitionJson());
        var steps=(com.fasterxml.jackson.databind.node.ArrayNode)tree.get("steps"); var last=steps.remove(steps.size()-1); steps.insert(0,last);
        assertThrows(IllegalArgumentException.class,()->d.validateSteps(tree.toString()));
        assertEquals(7,d.validateSteps(d.buildDefinitionJson()).size());
    }
}
