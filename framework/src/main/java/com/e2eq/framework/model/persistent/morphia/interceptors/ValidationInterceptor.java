package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.exceptions.E2eqValidationException;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.interfaces.InvalidSavable;
import com.e2eq.framework.model.general.ValidationViolation;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import io.quarkus.logging.Log;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.*;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class ValidationInterceptor implements EntityListener<Object> {
   @Inject
   Validator validator;

   @Inject
   ObjectMapper objectMapper;

   public ValidationInterceptor () {

   }

   @Override
   public boolean hasAnnotation(Class<? extends Annotation> type) {
      return false;
   }

   @Override
   public void prePersist (Object ent, Document document, Datastore datastore) {
      BaseModel bm = null;
      if (ent instanceof BaseModel) {
         bm = (BaseModel) ent;

         if (SecurityContext.getPrincipalContext().isPresent()) {
            if (bm.getDataDomain() == null) {
               DataDomain dd = SecurityContext.getPrincipalDataDomain().get();
               if (dd == null) {
                  throw new IllegalArgumentException(("Principal context not providing a data domain, ensure your logged in, or passing a data domain structure"));
               }
               bm.setDataDomain(dd);
                    /*DataDomain dd = new DataDomain();
                    dd.setOrgRefName(SecurityContext.getPrincipalContext().get().getDataDomain().getOrgRefName());
                    dd.setAccountNum(SecurityContext.getPrincipalContext().get().getDataDomain().getAccountNum());
                    dd.setTenantId(SecurityContext.getPrincipalContext().get().getDataDomain().getTenantId());
                    dd.setOwnerId(SecurityContext.getPrincipalContext().get().getUserId());
                    dd.setDataSegment(SecurityContext.getPrincipalContext().get().getDataDomain().getDataSegment()); */
            }
         }
      }

      final Set<ConstraintViolation<Object>> violationSet = validator.validate(ent);

      if (!violationSet.isEmpty()) {
         Set<ValidationViolation> violations = new HashSet<>(violationSet.size());
         for (ConstraintViolation<?> constraintViolation : violationSet) {
            ValidationViolation violation = new ValidationViolation();

            violation.setPropertyPath(constraintViolation.getPropertyPath().toString());
            violation.setViolationDescription(constraintViolation.getMessage());
            violations.add(violation);
         }

         if (ent instanceof InvalidSavable && ((InvalidSavable) ent).isCanSaveInvalid()) {
            InvalidSavable isaveable = (InvalidSavable) ent;
            isaveable.setViolationSet(null);
            isaveable.setViolationSet(violations);
         } else {
            E2eqValidationException ex = new E2eqValidationException();
            ex.setViolationSet(violationSet);
            ex.setJsonData(document.toJson().toString());
            violationSet.forEach(violation -> Log.error(violation.getMessage() + " :" + violation.getPropertyPath() +":" + violation.getRootBean().getClass().getName()));
            Log.error("Object:");
            try {
               Log.error(objectMapper.writeValueAsString(ent));
            } catch (JsonProcessingException e) {
               throw new RuntimeException(e);
            }
            throw ex;
         }
      }
   }
}
