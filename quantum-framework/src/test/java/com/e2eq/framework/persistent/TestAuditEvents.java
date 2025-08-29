package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.ParentModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

@QuarkusTest
public class TestAuditEvents extends BaseRepoTest {

    @Inject
    TestParentRepo testService;
    @Inject
    TestParentRepo testParentRepo;

    @Test
    public void testAuditEvents() throws ReferentialIntegrityViolationException {
        ParentModel testParentEntity = new ParentModel();

        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
            Optional<ParentModel> parentModel = testService.findByRefName("test-audit");
            if (parentModel.isPresent()) {
                testService.delete(parentModel.get());
                testParentEntity = new ParentModel();
            }
            testParentEntity.setRefName("test-audit");
            testParentEntity.setDisplayName("Test Audit Entity");
            testParentEntity = testService.save(testParentEntity);

            Assertions.assertNotNull(testParentEntity.getPersistentEvents());
            Assertions.assertTrue(testParentEntity.getPersistentEvents().size() == 1);

            Optional<ParentModel> optionalTestParentModel =  testService.findByRefName("test-audit");
            Assertions.assertTrue(optionalTestParentModel.isPresent());
            Assertions.assertTrue(optionalTestParentModel.get().getPersistentEvents() != null &&
                    optionalTestParentModel.get().getPersistentEvents().size() == 1);

            testParentRepo.delete(testParentEntity);

        }

    }
}
