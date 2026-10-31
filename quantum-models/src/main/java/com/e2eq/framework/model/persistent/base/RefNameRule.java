package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.exceptions.RefNameViolationException.Code;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The canonical refName rule shared with platform-ux {@code tenant-admin-ui/src/ref-name.ts}:
 * capital letters, digits and underscores, starting with a letter, 3-64 characters, and not a
 * generated-looking id (ObjectId, dashless UUID, hash). Values are judged as given; nothing is trimmed
 * or re-cased.
 */
public final class RefNameRule {
   public static final int MIN_LENGTH = 3;
   public static final int MAX_LENGTH = 64;
   public static final Pattern PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
   public static final Pattern GENERATED_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{16,}$");
   public static final String DESCRIPTION =
      "refName must match ^[A-Z][A-Z0-9_]*$, be " + MIN_LENGTH + "-" + MAX_LENGTH
         + " characters, and not be a generated id (^[0-9a-f]{16,}$, case-insensitive)";

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
