package com.e2eq.framework.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestJsonSchemaUtils {
   @Test
   public void testBasics() throws JsonProcessingException {
      JSONUtils utils = JSONUtils.instance();
      String schema = utils.getSchemaAsString(TestBean.class);
      Log.debug("Schema:"+ schema);
   }
}
