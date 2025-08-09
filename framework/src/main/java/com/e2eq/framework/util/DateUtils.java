package com.e2eq.framework.util;

import java.time.*;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class DateUtils {
   public static Date calculateDate(ZonedDateTime zt, ZoneId targetTimeZone) {
      // Represent the same instant in the target timezone and return the corresponding Date (instant)
      return Date.from(zt.withZoneSameInstant(targetTimeZone).toInstant());
   }

   public static ZonedDateTime getZonedDateTime (LocalDate date, LocalTime time, ZoneId zoneId) {
      try {
         return ZonedDateTime.of(date, time, zoneId);
      } catch (Exception e) {
         return null;
      }
   }

   public static Date parseDateTime (String inputDateTimeString) {
      try {
         return Date.from(Instant.parse(inputDateTimeString));
      } catch (Exception e) {
         return null;
      }
   }
}
