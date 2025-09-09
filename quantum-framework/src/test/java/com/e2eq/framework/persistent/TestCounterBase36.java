package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CounterRepo;
import com.e2eq.framework.model.securityrules.SecurityContext;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.e2eq.framework.securityrules.SecuritySession;

@QuarkusTest
public class TestCounterBase36 extends BaseRepoTest {

    @Inject
    CounterRepo counterRepo;

    @Test
    public void testGetAndIncrementEncoded_base36() {
        // Arrange principal/security context
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            DataDomain dd = SecurityContext.getPrincipalDataDomain().get();
            String counterName = "test-counter-base36";

            // Act: first increment should return current value before increment
            var cv0 = counterRepo.getAndIncrementEncoded(counterName, dd, 1L, 36);
            Log.info("cv0: " + cv0.toString());
            var cv1 = counterRepo.getAndIncrementEncoded(counterName, dd, 1L, 36);
            Log.info("cv1: " + cv1.toString());
            var cv2 = counterRepo.getAndIncrementEncoded(counterName, dd, 1L, 36);
            Log.info("cv2: " + cv2.toString());

            // Assert numeric values are monotonically increasing by 1
            Assertions.assertEquals(cv0.getValue() + 1, cv1.getValue());
            Assertions.assertEquals(cv1.getValue() + 1, cv2.getValue());

            // Assert base and encoded values
            Assertions.assertEquals(36, cv0.getBase());
            Assertions.assertEquals(Long.toString(cv0.getValue(), 36), cv0.getEncodedValue());
            Assertions.assertEquals(Long.toString(cv1.getValue(), 36), cv1.getEncodedValue());
            Assertions.assertEquals(Long.toString(cv2.getValue(), 36), cv2.getEncodedValue());
        }
    }
}
