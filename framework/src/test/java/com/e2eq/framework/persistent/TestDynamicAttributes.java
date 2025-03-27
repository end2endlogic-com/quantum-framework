package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.TestParentModel;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestDynamicAttributes extends BaseRepoTest {

    @Inject
    TestParentRepo testParentRepo;

    @Inject
    RuleContext ruleContext;

    @Test
    void testDynamicAttributes() throws ReferentialIntegrityViolationException {
        TestParentModel model = new TestParentModel();
        model.setTestField("testValue");
        DynamicAttributeSet dynamicAttributeSet = new DynamicAttributeSet();
        dynamicAttributeSet.setName("extendedAttributes");
        DynamicAttribute dynamicAttribute = new DynamicAttribute();
        dynamicAttribute.setName("testAttribute");
        dynamicAttribute.setValue("testAttributeValue");
        List< DynamicAttribute> attributes = new ArrayList<>();
        attributes.add(dynamicAttribute);
        dynamicAttributeSet.setAttributes(attributes);
        model.getDynamicAttributeSets().add(dynamicAttributeSet);

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            model  = testParentRepo.save(model);
            testParentRepo.delete(model);
        }


    }
}
