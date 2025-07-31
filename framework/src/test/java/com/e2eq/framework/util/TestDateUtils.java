package com.e2eq.framework.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.ToString;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.time.*;
import java.util.Calendar;
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
   ZoneId zoneId = ZoneId.of("America/New_York");
   ZonedDateTime dt = DateUtils.getZonedDateTime(date, time, zoneId);
   System.out.println("ZonedDateTime: " + dt);;
   ZoneId desiredTimezone  = ZoneId.of("America/Chicago");

   Date convertedDate = DateUtils.calculateDate(dt, desiredTimezone);
   // validate that the dateToConvert and the convertedDate are 1 hour apart with the convertedDate being 1 hour less
   SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
   sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
  System.out.println("Converted Date: " + sdf.format(convertedDate));
  // assert the time of the convertedDate is 1 hr earlier than the ZonedDateTime dt
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
