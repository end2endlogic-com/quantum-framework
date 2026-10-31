package com.e2eq.framework.exceptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed failure for an entity annotated with {@link com.e2eq.framework.annotations.CanonicalRefName}:
 * a new record whose refName breaks the canonical rule, or an update that tries to change refName.
 */
public class RefNameViolationException extends RuntimeException {
   private static final long serialVersionUID = 1L;

   public enum Code {
      /** No refName was supplied on create; it is not defaulted to the ObjectId. */
      REFNAME_REQUIRED,
      /** refName does not match the canonical pattern or length. */
      REFNAME_INVALID_FORMAT,
      /** refName looks like a generated id (ObjectId, dashless UUID, hash). */
      REFNAME_GENERATED_ID,
      /** A client tried to create a record with a declared legacy name reserved for framework code. */
      REFNAME_RESERVED,
      /** An update tried to change the refName of an existing record. */
      REFNAME_IMMUTABLE
   }

   private final Code code;
   private final String entityType;
   private final String entityId;
   private final String offeredRefName;
   private final String storedRefName;
   private final String rule;

   public RefNameViolationException(Code code, String entityType, String entityId,
                                    String offeredRefName, String storedRefName, String rule) {
      super(buildMessage(code, entityType, entityId, offeredRefName, storedRefName, rule));
      this.code = code;
      this.entityType = entityType;
      this.entityId = entityId;
      this.offeredRefName = offeredRefName;
      this.storedRefName = storedRefName;
      this.rule = rule;
   }

   private static String buildMessage(Code code, String entityType, String entityId,
                                      String offered, String stored, String rule) {
      return switch (code) {
         case REFNAME_IMMUTABLE -> String.format(
            "%s: refName of %s id=%s cannot change from '%s' to '%s'", code, entityType, entityId, stored, offered);
         case REFNAME_REQUIRED -> String.format(
            "%s: %s requires an explicit refName on create; %s", code, entityType, rule);
         default -> String.format(
            "%s: refName '%s' for new %s is not allowed; %s", code, offered, entityType, rule);
      };
   }

   public Code getCode() {
      return code;
   }

   public String getEntityType() {
      return entityType;
   }

   public String getEntityId() {
      return entityId;
   }

   public String getOfferedRefName() {
      return offeredRefName;
   }

   public String getStoredRefName() {
      return storedRefName;
   }

   public String getRule() {
      return rule;
   }

   /** Diagnostics for the REST payload; absent values are omitted. */
   public Map<String, String> diagnostics() {
      Map<String, String> d = new LinkedHashMap<>();
      d.put("code", code.name());
      d.put("entityType", entityType);
      if (entityId != null) d.put("entityId", entityId);
      if (offeredRefName != null) d.put("offeredRefName", offeredRefName);
      if (storedRefName != null) d.put("storedRefName", storedRefName);
      if (rule != null) d.put("rule", rule);
      return Collections.unmodifiableMap(d);
   }
}
