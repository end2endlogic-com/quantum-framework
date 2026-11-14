package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.FunctionalArea;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/** Functional areas of application vocabularies, held in the policy-store realm with the domains. */
@ApplicationScoped
public class FunctionalAreaRepo extends MorphiaRepo<FunctionalArea> {
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
   String defaultRealm;

   @ConfigProperty(name = "quantum.security.policy-store-realm")
   Optional<String> policyStoreRealm = Optional.empty();

   @Override
   public String getSecurityContextRealmId() {
      return policyStoreRealm
         .filter(value -> !value.isBlank())
         .map(String::trim)
         .orElse(defaultRealm);
   }

   @Override
   protected void setDefaultValues(FunctionalArea model) {
      super.setDefaultValues(model);
      if (model.getApplicationId() == null || model.getApplicationId().isBlank()) {
         throw new IllegalArgumentException("FunctionalArea.applicationId is required: an area belongs to one application");
      }
   }

   /** Every area, whatever its application, ignoring rules (the vocabulary registry's read). */
   public List<FunctionalArea> findAllWithIgnoreRules() {
      try (dev.morphia.query.MorphiaCursor<FunctionalArea> cursor =
              morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).find(FunctionalArea.class).iterator()) {
         return cursor.toList();
      }
   }
}
