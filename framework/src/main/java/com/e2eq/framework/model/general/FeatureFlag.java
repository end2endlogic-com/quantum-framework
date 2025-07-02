package com.e2eq.framework.model.general;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.fasterxml.jackson.databind.JsonNode;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
@Entity
public class FeatureFlag extends BaseModel {

   // Flag Management Interface: Attributes for creating/updating flags
   private String description;          // Optional description for context
   private boolean enabled;             // Overall flag status (on/off)
   // Last update timestamp
   private String createdBy;            // User who created the flag
   private String updatedBy;            // User who last updated the flag
   // Boolean and Multivariate Flags: Support for simple and complex flags
   private FlagType type;               // Enum to indicate boolean or multivariate
   private List<String> variants;       // List of variants for multivariate flags (e.g., ["v1", "v2"])
   // Targeting and Segmentation: Rules for user/group targeting
   private List<TargetRule> targetRules;// Rules defining which users/groups get the flag
   private Map<String, String> customAttributes; // Key-value pairs for custom user properties (e.g., "plan=premium")
   // Environment Support: Environment-specific configuration
   private String environment;          // Environment (e.g., "dev", "staging", "prod")
   private JsonNode jsonConfiguration;  // Configuration of feature in JSON format

   @Override
   public String bmFunctionalArea () {
      return "FEATURES";
   }

   @Override
   public String bmFunctionalDomain () {
      return "FEATURE_FLAGS";
   }

   // Enum for flag type
   public enum FlagType {
      BOOLEAN, MULTIVARIATE
   }
   // Nested class for targeting rules
   @Data
   @Builder
   @NoArgsConstructor
   @AllArgsConstructor
   public static class TargetRule {
      private String attribute;        // Attribute to target (e.g., "userId", "location")
      private String operator;         // Operator (e.g., "equals", "contains")
      private List<String> values;     // Values to match (e.g., ["user123", "user456"])
      private String variant;          // Optional: Specific variant for this rule (multivariate flags)
   }
}
