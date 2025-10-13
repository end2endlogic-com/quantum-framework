package com.e2eq.framework.test;

import com.e2eq.framework.persistent.TestParentRepo;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TestEquals {
   @Inject
   TestParentRepo parentRepo;

   @Test
   public void testBasicEquals() {
      ParentModel t = new ParentModel();
      t.setTestField("test");
      ParentModel t2 = new ParentModel();
      t2.setTestField("test");
      Assertions.assertEquals(t, t2);
      ParentModel t3 = parentRepo.save(t);
      Optional<ParentModel> t4 = parentRepo.findById(t3.getId().toHexString(), true);
      assertTrue(t4.isPresent());
      Assertions.assertEquals(t3, t4.get());



   }
}
