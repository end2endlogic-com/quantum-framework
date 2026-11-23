package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.exceptions.RefNameViolationException.Code;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The canonical refName rule shared with platform-ux {@code tenant-admin-ui/src/ref-name.ts}.
 * A refName is one of exactly two things: not given, in which case the repository sets it to the record's
 * ObjectId, or an upper-case name (capital letters, digits and underscores, starting with a letter, 3-64
 * characters). Any other generated-looking value (an ObjectId, dashless UUID or hash that is not the record's own id) is refused: to
 * get the ObjectId, omit refName. Values are judged as given; nothing is trimmed or re-cased.
 */
public final class RefNameRule {
   public static final int MIN_LENGTH = 3;
   public static final int MAX_LENGTH = 64;
   public static final Pattern PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
   public static final Pattern GENERATED_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{16,}$");
   /**
    * The two accepted forms, stated for developers in every refusal: omit refName to get the ObjectId default,
    * or give an upper-case name. Anything else (lower case, dashes, an id the caller made up) is refused.
    */
   public static final String DESCRIPTION =
      "refName must be either the record's own ObjectId (omit refName and it is set to the id for you) or an "
         + "upper-case name matching ^[A-Z][A-Z0-9_]*$ of " + MIN_LENGTH + "-" + MAX_LENGTH
         + " characters (for example OPS_TEAM); any other value, including an ObjectId, UUID or hash that is not "
         + "this record's id, is refused";

   private RefNameRule() {
   }

   /**
    * @return the violation code, or empty when {@code refName} satisfies the rule
    */
   public static Optional<Code> check(String refName) {
      if (refName == null || refName.isEmpty()) {
         return Optional.of(Code.REFNAME_REQUIRED);
      }
      if (GENERATED_ID_PATTERN.matcher(refName).matches()) {
         return Optional.of(Code.REFNAME_GENERATED_ID);
      }
      if (refName.length() < MIN_LENGTH || refName.length() > MAX_LENGTH
             || !PATTERN.matcher(refName).matches()) {
         return Optional.of(Code.REFNAME_INVALID_FORMAT);
      }
      return Optional.empty();
   }
}
