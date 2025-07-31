package com.e2eq.framework.util;

import java.time.*;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

public class DateUtils {
   public static Date calculateDate(ZonedDateTime zt, ZoneId targetTimeZone) {
      ZonedDateTime converted = zt.withZoneSameInstant(targetTimeZone);
      LocalDateTime localInTarget = converted.toLocalDateTime();
      ZoneId originalZone = zt.getZone();
      ZonedDateTime adjusted = ZonedDateTime.of(localInTarget, originalZone);
      return Date.from(adjusted.toInstant());
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
