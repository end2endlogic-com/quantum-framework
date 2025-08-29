package com.e2eq.framework.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.*;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class TestDateUtils {

   @Inject
   ObjectMapper mapper;

   @Test
   public void testTimezoneConversion() {
      LocalDate date = LocalDate.of(2022, 1, 1);
      LocalTime time = LocalTime.of(12, 0);
      ZoneId ny = ZoneId.of("America/New_York");
      ZonedDateTime dtNY = DateUtils.getZonedDateTime(date, time, ny);
      ZoneId chicago  = ZoneId.of("America/Chicago");

      Date convertedDate = DateUtils.calculateDate(dtNY, chicago);

      // The conversion should preserve the instant, i.e., equal to dtNY instant
      assertEquals(dtNY.toInstant(), convertedDate.toInstant());

      // And when rendering that instant in Chicago, the local wall time should be one hour earlier than NY on this date
      ZonedDateTime dtChicagoSameInstant = dtNY.withZoneSameInstant(chicago);

      SimpleDateFormat sdfNY = new SimpleDateFormat("yyyy-MM-dd HH:mm");
      sdfNY.setTimeZone(TimeZone.getTimeZone("America/New_York"));
      SimpleDateFormat sdfCHI = new SimpleDateFormat("yyyy-MM-dd HH:mm");
      sdfCHI.setTimeZone(TimeZone.getTimeZone("America/Chicago"));

      String nyRendered = sdfNY.format(Date.from(dtNY.toInstant()));
      String chiRendered = sdfCHI.format(convertedDate);

      // Expect 11:00 in Chicago when it's 12:00 in New York for this date
      org.junit.jupiter.api.Assertions.assertTrue(nyRendered.endsWith("12:00"));
      org.junit.jupiter.api.Assertions.assertTrue(chiRendered.endsWith("11:00"));
   }


   @Test
   public void testZonedDateTime() {
      LocalDateTime localStartTime = LocalDateTime.of(2025, 7, 30, 9, 0);

      // Step 2: Define the locations and their zones
      List<ZoneId> zones = List.of(
         ZoneId.of("America/New_York"),   // Eastern Time
         ZoneId.of("America/Chicago"),    // Central Time
         ZoneId.of("America/Los_Angeles") // Pacific Time
      );

      // Step 3: Convert to java.util.Date for each zone
      for (ZoneId zone : zones) {
         // Attach the wall-clock time to the specific zone
         ZonedDateTime zonedDateTime = localStartTime.atZone(zone);

         // Convert to java.util.Date (which represents the instant globally)
         Date instantDate = Date.from(zonedDateTime.toInstant());

         // Display the result in that zone for clarity
         System.out.println(zone + " -> Local: " + zonedDateTime +
                               " | UTC Instant: " + instantDate);
      }
   }

   @Test
   public void testLocalDateTimeSerialization() throws JsonProcessingException {
     LocalDateTime now = LocalDateTime.now();
     String serialized = mapper.writeValueAsString(now);
     Log.info(serialized);

   }
}
