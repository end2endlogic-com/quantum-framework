package com.e2eq.framework.api.mapper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

@QuarkusTest
public class TestMapperFoundation {

   /* @Test
   public void testJsurferCollector() {
      JsonSurfer surfer = new JsonSurfer(JacksonParser.INSTANCE, JacksonProvider.INSTANCE);
      InputStream in = this.getClass().getClassLoader().getResourceAsStream("testData/SampleJson.json");

      Collector collector = surfer.collector(in);
      ValueBox<String> box1 = collector.collectOne("$.store.book[1].category", String.class);
      collector.exec();
      assertEquals(box1.get(), "fiction");
   } */


   /*
    Shows how to use jsurfer to use xpath like statements to parse
   @Test
   public void testJsurferBinding() {
      JsonSurfer surfer = new JsonSurfer(JacksonParser.INSTANCE, JacksonProvider.INSTANCE);
      InputStream in = this.getClass().getClassLoader().getResourceAsStream("testData/SampleJson.json");

      // Get me all the books under a given store.
      // The listener context provides a way to "push to an output stream" vs. using a
      // holder mechanism which will store the values in memory.
      surfer.configBuilder().bind("$.store.book[*]", new JsonPathListener() {
         @Override
         public void onValue (Object value, ParsingContext parsingContext) {
           Log.info(value);

           if (value instanceof JsonNode) {
              JsonNode node = (JsonNode) value;
              JsonNodeType type = node.getNodeType();
              Log.info(" >> Type:" + type.toString() + ": Path:" + parsingContext.getJsonPath());
           } else {
              Log.info ( " >> Unknown JsonNodetype:" + value.getClass().getName());
           }

         }
      }).buildAndSurf(in);
   } */

   /**
    Creates a tree in memory of the given json structure.
    @throws IOException
   @Test public void testJacksonTree() throws IOException {
      ObjectMapper mapper = new ObjectMapper();
      CsvMapper csvMapper = new CsvMapper();
      csvMapper.configure(JsonGenerator.Feature.IGNORE_UNKNOWN, true);

      CsvSchema schema = CsvSchema.builder()
         .addColumn("category")
         .addColumn("author")
         .addColumn("title")
         .addColumn("price")
         .build();

      InputStream in = this.getClass().getClassLoader().getResourceAsStream("testData/SimpleJson.json");
      JsonNode root=mapper.readTree(in);
      // This will convert a flat json to a csv.  In the case of this simple json
      // it was contrived to match so it will just work, however in cases where there are embedded objects
      // etc the tree will have to be flattened to make this work.
      csvMapper.writerFor(JsonNode.class).with(schema).writeValue(System.out, root);


      // Shows how to navigate a tree structure
     /* if (root.isArray()) {
         for (JsonNode arrayItem : root) {
            Iterator<Map.Entry<String, JsonNode>> fields = arrayItem.fields();
            while (fields.hasNext()) {
               Map.Entry<String, JsonNode> entry = fields.next();
               Log.info("Field:" + entry.getKey() + " Value:" + entry.getValue());
            }
         }
      }

      Log.info("----- Complete");
   } */

   public void displayToken(JsonToken token,JsonParser parser  ) throws IOException {
      switch (token) {
         case NOT_AVAILABLE:
            if (Log.isDebugEnabled())
               Log.debug("pause");
            break;
         case START_OBJECT:
            if (Log.isDebugEnabled())
               Log.debug("{> Start Object");
            break;
         case END_OBJECT:
            if (Log.isDebugEnabled())
               Log.debug("}< End Object");
            break;
         case START_ARRAY:
            if (Log.isDebugEnabled())
               Log.debug("[>> Start Array");
            break;
         case END_ARRAY:
            if (Log.isDebugEnabled())
               Log.debug("}<< End Array");
            break;
         case FIELD_NAME:
            if (Log.isDebugEnabled())
               Log.debug("  Field Name:" + parser.currentName());
            break;
         case VALUE_STRING:
            if (Log.isDebugEnabled())
               Log.debug("   String:" + parser.getValueAsString());
            break;
         case VALUE_NUMBER_INT:
            if (Log.isDebugEnabled())
               Log.debugf("   Int:%d" , parser.getValueAsInt());
            break;
         case VALUE_NUMBER_FLOAT:
            if (Log.isDebugEnabled())
               Log.debugf("   Float:%f" , parser.getValueAsDouble());
            break;
         case VALUE_TRUE:
            if (Log.isDebugEnabled())
               Log.debugf("   TBoolean:%s" ,parser.getValueAsBoolean());
            break;
         case VALUE_FALSE:
            if (Log.isDebugEnabled())
               Log.debugf("   FBoolean:%s" , parser.getValueAsBoolean());
            break;
         case VALUE_NULL:
            if (Log.isDebugEnabled())
               Log.debug("   NULL VALUE:");
            break;
         case VALUE_EMBEDDED_OBJECT:
            if (Log.isDebugEnabled())
               Log.debug("   EmbeddedObject:");
      }
   }

   @Test
   public void testStreaming() throws IOException {

        InputStream in = this.getClass().getClassLoader().getResourceAsStream("testData/SampleJson.json");
         JsonFactory jsonFactory = new JsonFactory();

         try (JsonParser parser = jsonFactory.createParser(in)) {
            JsonToken token = parser.nextToken();
            while( token != null) {
               displayToken(token, parser);
               token = parser.nextToken();
            }
         }
   }


}
