package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.FunctionalDomain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;



@ApplicationScoped
public class FunctionalDomainRepo extends MorphiaRepo<FunctionalDomain>{
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
   String defaultRealm;

   @ConfigProperty(name = "quantum.security.policy-store-realm")
   Optional<String> policyStoreRealm = Optional.empty();

   @ConfigProperty(name = "quantum.security.policy-admission.enabled", defaultValue = "false")
   boolean policyAdmissionEnabled;

   @Inject
   ApplicationRepo applicationRepo;
   @Override
   public String getSecurityContextRealmId() {
      return policyStoreRealm
         .filter(value -> !value.isBlank())
         .map(String::trim)
         .orElse(defaultRealm);
   }

   @Override
   protected void setDefaultValues(FunctionalDomain model) {
      super.setDefaultValues(model);
      if (!policyAdmissionEnabled) return;
      if (model.getApplicationId() == null || model.getApplicationId().isBlank()) {
         throw new IllegalArgumentException("FunctionalDomain.applicationId is required");
      }
      if (!"*".equals(model.getApplicationId().trim())
            && applicationRepo.findByRefNameWithIgnoreRules(model.getApplicationId()).isEmpty()) {
         throw new IllegalArgumentException(
            "FunctionalDomain references unknown application: " + model.getApplicationId());
      }
   }

   public List<FunctionalDomain> findForApplicationWithIgnoreRules(String applicationId) {
      if (applicationId == null || applicationId.isBlank()) return List.of();
      dev.morphia.query.MorphiaCursor<FunctionalDomain> cursor =
         morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
         .find(FunctionalDomain.class)
         .iterator();
      try (cursor) {
         return cursor.toList().stream()
            .filter(domain -> domain.getApplicationId() != null
               && ("*".equals(domain.getApplicationId().trim())
                  || domain.getApplicationId().trim().equalsIgnoreCase(applicationId.trim())))
            .toList();
      }
   }
}
