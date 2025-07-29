package com.e2eq.framework.util;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;

public class DateUtils {
   public static Date calculateLocalDate(Date dateToConvert, TimeZone desiredTimezone) {
      if (dateToConvert == null || desiredTimezone == null) {
         throw new IllegalArgumentException("Date and TimeZone must not be null");
      }

      // Step 1: Convert the Date (which is UTC-based) to an Instant
      Instant instant = dateToConvert.toInstant();

      // Step 2: Apply the desired time zone
      ZonedDateTime zonedDateTime = instant.atZone(desiredTimezone.toZoneId());

      // Step 3: Return as a Date, representing the *wall-clock time* in the new time zone
      return Date.from(zonedDateTime.toInstant());
   }
}
