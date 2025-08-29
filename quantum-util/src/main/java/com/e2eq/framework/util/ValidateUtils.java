package com.e2eq.framework.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ValidateUtils {
   private static final String EMAIL_PATTERN =
      "^(?=.{1,64}@)[A-Za-z0-9.%+-]+(\\.[A-Za-z0-9_-]+)*@"
         + "[^-][A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(\\.[A-Za-z]{2,})$";

   private static final Pattern pattern = Pattern.compile(EMAIL_PATTERN);

   public static boolean isValidEmailAddress(final String email) {
      if (email == null || email.isEmpty()) {
         return false;
      }

      Matcher matcher = pattern.matcher(email);
      return matcher.matches();
   }

   public static void nonNullCheck(Object value, String fieldName) {
      if (value == null) {
         throw new IllegalArgumentException(" " + fieldName + " cannot be null");
      }
   }

   public static void nonEmptyCheck(String value, String fieldName) {
      if (value == null || value.isEmpty()) {
         throw new IllegalArgumentException(" " + fieldName + " cannot be empty");
      }
   }
}
