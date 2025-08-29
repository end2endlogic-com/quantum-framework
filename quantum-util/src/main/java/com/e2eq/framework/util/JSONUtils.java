package com.e2eq.framework.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;


import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchemaGenerator;

public class JSONUtils {
   private static final JSONUtils instance = new JSONUtils();
   protected ObjectMapper mapper;
   protected JsonSchemaGenerator schemaGen;

   private JSONUtils() {
      mapper = new ObjectMapper();
    //  mapper.registerModule(new JaxbAnnotationModule());
      schemaGen = new JsonSchemaGenerator(mapper);
   }

   public static JSONUtils instance() {
      return instance;
   }

   public String getSchemaAsString(Class<?> clazz) throws JsonProcessingException {
      JsonSchema schema = schemaGen.generateSchema(clazz);
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
   }

   public JsonSchema getSchema(Class<?> clazz) throws JsonMappingException {
      return schemaGen.generateSchema(clazz);
   }
}
