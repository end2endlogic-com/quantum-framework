package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.test.ObjectRefModel;
import com.e2eq.framework.test.ParentModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TestObjectReference extends BaseRepoTest {

    @Inject
    RuleContext ruleContext;

    @Inject
    TestObjectRefRepo testObjectRefRepo;

    @Inject
    TestParentRepo testParentRepo;

    @Test
    public void testObjectReference() throws ReferentialIntegrityViolationException {

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            // Create a parent object
            ParentModel parent = new ParentModel();
            parent.setTestField("parent");
            parent = testParentRepo.save(parent);

            // Create a child object referencing the parent
            ObjectRefModel child = new ObjectRefModel();
            child.setTestField("child");
            child.setParent(parent);
            testObjectRefRepo.save(child);

            // Verify the child object has the correct reference to the parent
            Optional<ObjectRefModel> retrievedChild = testObjectRefRepo.findById(child.getId());
            assertTrue(retrievedChild.isPresent());
            assertTrue(retrievedChild.get().getParent().getId().equals(parent.getId()));

            testObjectRefRepo.delete(child);
            testParentRepo.delete(parent);
        }
    }
}
