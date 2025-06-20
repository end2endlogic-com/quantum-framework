package com.e2eq.framework.persistent;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.base.StateGraphManager;

import com.e2eq.framework.model.securityrules.SecuritySession;

import com.e2eq.framework.test.TestOrder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

@QuarkusTest
public class TestStateful extends BaseRepoTest  {
   @Inject
   StateGraphManager stateManager;

   @Inject
   TestOrderRepo repo;



   @Test
   public void testInitialState() {
      stateManager.getStateGraphs().forEach((key, nnode) -> {
         System.out.println(stateManager.printStateGraph(key));
      });
   }

   @Test
   public void testSave() throws ReferentialIntegrityViolationException {
      TestOrder test = new TestOrder();
      test.setOrderStatus("PENDING");
      test.setRefName("testOrder1");
      try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
         // find testOrder1 first and delete if its there
         Optional<TestOrder> existing = repo.findByRefName("testOrder1");
         if (existing.isPresent()) {
            repo.delete(existing.get());
         }
         test = repo.save(test);

         test.setOrderStatus("PROCESSING");
         test = repo.save(test);

         try {
            test.setOrderStatus("INVALID");
            repo.save(test);
            Assertions.fail("Expected InvalidStateTransitionException to be thrown");
         } catch (RuntimeException e) {
         }





      }

   }
}
