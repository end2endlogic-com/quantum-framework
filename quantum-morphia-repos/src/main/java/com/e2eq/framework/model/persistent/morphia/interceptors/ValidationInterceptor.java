package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.exceptions.E2eqValidationException;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.general.interfaces.InvalidSavable;

import com.e2eq.framework.model.persistent.base.ValidationViolation;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
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

   @Inject
   com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy.DataDomainResolver dataDomainResolver;

   public ValidationInterceptor () {

   }

   @Override
   public boolean hasAnnotation(Class<? extends Annotation> type) {
      return false;
   }

   @Override
   public void prePersist (Object ent, Document document, Datastore datastore) {
      UnversionedBaseModel bm = null;
      boolean skipValidation = false;

      if (ent instanceof UnversionedBaseModel) {
         bm = (UnversionedBaseModel) ent;
         if (!bm.isSkipValidation()) {
            if (SecurityContext.getPrincipalContext().isPresent()) {
               if (bm.getDataDomain() == null) {
                  DataDomain dd = dataDomainResolver.resolveForCreate(bm.bmFunctionalArea(), bm.bmFunctionalDomain());
                  if (dd == null) {
                     throw new IllegalStateException("Resolved data domain is null, this should not happen");
                  }
                  bm.setDataDomain(dd);
               }
            }
         } else {
            skipValidation = true;
         }
      }

      if (!skipValidation) {
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
               E2eqValidationException ex = new E2eqValidationException(violationSet, document.toJson().toString());
               ex.setViolationSet(violationSet);
               ex.setJsonData(document.toJson().toString());
               violationSet.forEach(violation -> Log.error(violation.getMessage() + " :" + violation.getPropertyPath() + ":" + violation.getRootBean().getClass().getName()));
               Log.error("Object:");
               try {
                  Log.error(objectMapper.writeValueAsString(ent));
               } catch (JsonProcessingException e) {
                  Log.error("Error processing entity for validation:", e);
                  throw new RuntimeException(e);
               }
               throw ex;
            }
         }
      }
   }
}
