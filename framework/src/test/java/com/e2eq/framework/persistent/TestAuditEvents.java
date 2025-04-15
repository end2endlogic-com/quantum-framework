package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.TestParentModel;
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
        TestParentModel testParentEntity = new TestParentModel();

        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
            Optional<TestParentModel> parentModel = testService.findByRefName("test-audit");
            if (parentModel.isPresent()) {
                testService.delete(parentModel.get());
                testParentEntity = new TestParentModel();
            }
            testParentEntity.setRefName("test-audit");
            testParentEntity.setDisplayName("Test Audit Entity");
            testParentEntity = testService.save(testParentEntity);

            Assertions.assertNotNull(testParentEntity.getPersistentEvents());
            Assertions.assertTrue(testParentEntity.getPersistentEvents().size() == 1);

            Optional<TestParentModel> optionalTestParentModel =  testService.findByRefName("test-audit");
            Assertions.assertTrue(optionalTestParentModel.isPresent());
            Assertions.assertTrue(optionalTestParentModel.get().getPersistentEvents() != null &&
                    optionalTestParentModel.get().getPersistentEvents().size() == 1);

            testParentRepo.delete(testParentEntity);

        }

    }
}
